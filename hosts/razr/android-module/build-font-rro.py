#!/usr/bin/env python3
"""
Pure-Python Android RRO (Runtime Resource Overlay) APK builder for system font packs.

Generates an unsigned, 4-byte-aligned APK containing:
  - resources.arsc (uncompressed, 4-byte aligned, per Android 11+ PackageManager rules)
  - AndroidManifest.xml (binary AXML targeting `android` with category
    `android.theme.customization.font`)

This avoids any dependency on x86_64-only `aapt2` binaries so `razr-android-module`
builds natively and reproducibly on both `aarch64-linux` and `x86_64-linux`.
"""

import argparse
import struct
import zipfile
from pathlib import Path

# Android resource chunk types
RES_STRING_POOL_TYPE = 0x0001
RES_TABLE_TYPE = 0x0002
RES_XML_TYPE = 0x0003
RES_XML_START_NAMESPACE_TYPE = 0x0100
RES_XML_END_NAMESPACE_TYPE = 0x0101
RES_XML_START_ELEMENT_TYPE = 0x0102
RES_XML_END_ELEMENT_TYPE = 0x0103
RES_XML_RESOURCE_MAP_TYPE = 0x0180
RES_TABLE_PACKAGE_TYPE = 0x0200
RES_TABLE_TYPE_TYPE = 0x0201
RES_TABLE_TYPE_SPEC_TYPE = 0x0202

# Res_value data types
TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_BOOLEAN = 0x12

UTF8_FLAG = 1 << 8
NO_ENTRY = 0xFFFFFFFF


def _pad4(data: bytes) -> bytes:
    rem = len(data) & 3
    return data if rem == 0 else data + b"\x00" * (4 - rem)


def _encode_utf8_len(n: int) -> bytes:
    if n < 0x80:
        return bytes([n])
    return bytes([((n >> 8) & 0x7F) | 0x80, n & 0xFF])


def _encode_utf16_len(n: int) -> bytes:
    if n < 0x8000:
        return struct.pack("<H", n)
    return struct.pack("<HH", ((n >> 16) & 0x7FFF) | 0x8000, n & 0xFFFF)


def build_string_pool(strings: list[str], utf8: bool) -> bytes:
    offsets = []
    blob = bytearray()
    for s in strings:
        offsets.append(len(blob))
        if utf8:
            u16_len = len(s.encode("utf-16le")) // 2
            u8 = s.encode("utf-8")
            blob.extend(_encode_utf8_len(u16_len))
            blob.extend(_encode_utf8_len(len(u8)))
            blob.extend(u8)
            blob.append(0)
        else:
            u16 = s.encode("utf-16le")
            blob.extend(_encode_utf16_len(len(u16) // 2))
            blob.extend(u16)
            blob.extend(b"\x00\x00")

    blob_padded = _pad4(bytes(blob))
    header_size = 28
    offsets_bytes = struct.pack(f"<{len(offsets)}I", *offsets)
    strings_start = header_size + len(offsets_bytes)
    total_size = strings_start + len(blob_padded)
    flags = UTF8_FLAG if utf8 else 0

    header = struct.pack(
        "<HHIIIIII",
        RES_STRING_POOL_TYPE,
        header_size,
        total_size,
        len(strings),
        0,  # styleCount
        flags,
        strings_start,
        0,  # stylesStart
    )
    return header + offsets_bytes + blob_padded


def build_arsc(package_name: str, app_label: str, body_family: str, time_family: str) -> bytes:
    """Build a minimal valid resources.arsc for an Android font RRO overlay."""
    # Global value string pool
    if time_family == body_family:
        global_strings = [app_label, body_family]
        body_idx = 1
        time_idx = 1
    else:
        global_strings = [app_label, body_family, time_family]
        body_idx = 1
        time_idx = 2

    global_pool = build_string_pool(global_strings, utf8=True)

    # Package type & key string pools
    type_pool = build_string_pool(["string"], utf8=True)
    key_names = [
        "app_name",
        "config_bodyFontFamily",
        "config_bodyFontFamilyMedium",
        "config_headlineFontFamily",
        "config_headlineFontFamilyMedium",
        "config_timeFontFamily",
    ]
    key_pool = build_string_pool(key_names, utf8=True)

    # RES_TABLE_TYPE_SPEC_TYPE (id=1 -> "string", 6 entries, flags=0)
    spec_header_size = 16
    spec_total_size = spec_header_size + 4 * len(key_names)
    type_spec = struct.pack(
        "<HHIBBHI",
        RES_TABLE_TYPE_SPEC_TYPE,
        spec_header_size,
        spec_total_size,
        1,  # id (string)
        0,  # res0
        0,  # res1
        len(key_names),
    ) + struct.pack(f"<{len(key_names)}I", *([0] * len(key_names)))

    # RES_TABLE_TYPE_TYPE (id=1 -> "string", default config, 32-bit offsets)
    # ResTable_config: 64 bytes (size=64, rest zeros = default configuration)
    config = struct.pack("<I", 64) + b"\x00" * 60
    type_header_size = 20 + len(config)  # 84 bytes
    entry_value_indices = [0, body_idx, body_idx, body_idx, body_idx, time_idx]

    entries_blob = bytearray()
    entry_offsets = []
    for key_idx, str_idx in enumerate(entry_value_indices):
        entry_offsets.append(len(entries_blob))
        # ResTable_entry (8 bytes): size=8, flags=0, key=key_idx
        entries_blob.extend(struct.pack("<HHI", 8, 0, key_idx))
        # Res_value (8 bytes): size=8, res0=0, dataType=TYPE_STRING, data=str_idx
        entries_blob.extend(struct.pack("<HBBI", 8, 0, TYPE_STRING, str_idx))

    offsets_blob = struct.pack(f"<{len(entry_offsets)}I", *entry_offsets)
    entries_start = type_header_size + len(offsets_blob)
    type_total_size = entries_start + len(entries_blob)

    type_chunk = (
        struct.pack(
            "<HHIBBHII",
            RES_TABLE_TYPE_TYPE,
            type_header_size,
            type_total_size,
            1,  # id
            0,  # flags (32-bit offsets)
            0,  # reserved
            len(key_names),
            entries_start,
        )
        + config
        + offsets_blob
        + bytes(entries_blob)
    )

    # RES_TABLE_PACKAGE_TYPE (header=288 bytes)
    pkg_header_size = 288
    pkg_name_u16 = package_name.encode("utf-16le")
    if len(pkg_name_u16) > 254:
        raise ValueError(f"Package name too long: {package_name}")
    pkg_name_field = pkg_name_u16 + b"\x00" * (256 - len(pkg_name_u16))

    type_strings_offset = pkg_header_size
    key_strings_offset = pkg_header_size + len(type_pool)
    pkg_body = type_pool + key_pool + type_spec + type_chunk
    pkg_total_size = pkg_header_size + len(pkg_body)

    pkg_header = (
        struct.pack("<HHII", RES_TABLE_PACKAGE_TYPE, pkg_header_size, pkg_total_size, 0x7F)
        + pkg_name_field
        + struct.pack(
            "<IIIII",
            type_strings_offset,
            1,  # lastPublicType
            key_strings_offset,
            len(key_names),  # lastPublicKey
            0,  # typeIdOffset
        )
    )
    assert len(pkg_header) == pkg_header_size

    pkg_chunk = pkg_header + pkg_body

    # RES_TABLE_TYPE root chunk (header=12 bytes)
    table_total_size = 12 + len(global_pool) + len(pkg_chunk)
    table_header = struct.pack("<HHII", RES_TABLE_TYPE, 12, table_total_size, 1)
    return table_header + global_pool + pkg_chunk


def build_manifest_axml(package_name: str, sdk_version: int = 35) -> bytes:
    """Build binary AndroidManifest.xml (AXML) for a font RRO overlay."""
    # Strings with Android resource IDs MUST come first in the pool, in matching order.
    attr_res_ids = [
        ("label", 0x01010001),
        ("hasCode", 0x0101000C),
        ("priority", 0x0101001C),
        ("targetPackage", 0x01010021),
        ("minSdkVersion", 0x0101020C),
        ("versionCode", 0x0101021B),
        ("versionName", 0x0101021C),
        ("targetSdkVersion", 0x01010270),
        ("category", 0x010103E8),
        ("extractNativeLibs", 0x010104EA),
        ("compileSdkVersion", 0x01010572),
        ("compileSdkVersionCodename", 0x01010573),
    ]
    other_strings = [
        "1.0",
        "15",
        "android",
        "android.theme.customization.font",
        "application",
        package_name,
        "http://schemas.android.com/apk/res/android",
        "manifest",
        "overlay",
        "package",
        "platformBuildVersionCode",
        "platformBuildVersionName",
        "uses-sdk",
    ]
    strings = [name for name, _ in attr_res_ids] + other_strings
    idx = {s: i for i, s in enumerate(strings)}

    str_pool = build_string_pool(strings, utf8=False)

    res_ids = [rid for _, rid in attr_res_ids]
    res_map_size = 8 + 4 * len(res_ids)
    res_map = struct.pack("<HHI", RES_XML_RESOURCE_MAP_TYPE, 8, res_map_size) + struct.pack(
        f"<{len(res_ids)}I", *res_ids
    )

    ANDROID_NS = idx["http://schemas.android.com/apk/res/android"]
    ANDROID_PREFIX = idx["android"]

    def xml_node_header(chunk_type: int, total_size: int, line: int = 1) -> bytes:
        return struct.pack("<HHIII", chunk_type, 16, total_size, line, NO_ENTRY)

    def start_ns() -> bytes:
        return xml_node_header(RES_XML_START_NAMESPACE_TYPE, 24) + struct.pack(
            "<II", ANDROID_PREFIX, ANDROID_NS
        )

    def end_ns() -> bytes:
        return xml_node_header(RES_XML_END_NAMESPACE_TYPE, 24) + struct.pack(
            "<II", ANDROID_PREFIX, ANDROID_NS
        )

    def start_elem(tag: str, attrs: list[tuple[int, str, int, int, int]]) -> bytes:
        # attrs: list of (ns_idx, name_str, raw_str_idx, value_type, value_data)
        attr_blob = bytearray()
        for ns_i, name_s, raw_i, vtype, vdata in attrs:
            attr_blob.extend(
                struct.pack(
                    "<IIIHBBi",
                    ns_i,
                    idx[name_s],
                    raw_i,
                    8,  # Res_value.size
                    0,  # Res_value.res0
                    vtype,
                    vdata,
                )
            )
        # ResXMLTree_attrExt = 20 bytes + attrs
        total = 16 + 20 + len(attr_blob)
        return (
            xml_node_header(RES_XML_START_ELEMENT_TYPE, total)
            + struct.pack(
                "<IIHHHHHH",
                NO_ENTRY,  # element namespace
                idx[tag],
                20,  # attributeStart
                20,  # attributeSize
                len(attrs),
                0,  # idIndex
                0,  # classIndex
                0,  # styleIndex
            )
            + bytes(attr_blob)
        )

    def end_elem(tag: str) -> bytes:
        return xml_node_header(RES_XML_END_ELEMENT_TYPE, 24) + struct.pack(
            "<II", NO_ENTRY, idx[tag]
        )

    body = b"".join(
        [
            start_ns(),
            start_elem(
                "manifest",
                [
                    (ANDROID_NS, "versionCode", NO_ENTRY, TYPE_INT_DEC, 1),
                    (ANDROID_NS, "versionName", idx["1.0"], TYPE_STRING, idx["1.0"]),
                    (ANDROID_NS, "compileSdkVersion", NO_ENTRY, TYPE_INT_DEC, sdk_version),
                    (ANDROID_NS, "compileSdkVersionCodename", idx["15"], TYPE_STRING, idx["15"]),
                    (NO_ENTRY, "package", idx[package_name], TYPE_STRING, idx[package_name]),
                    (NO_ENTRY, "platformBuildVersionCode", NO_ENTRY, TYPE_INT_DEC, sdk_version),
                    (NO_ENTRY, "platformBuildVersionName", NO_ENTRY, TYPE_INT_DEC, 15),
                ],
            ),
            start_elem(
                "uses-sdk",
                [
                    (ANDROID_NS, "minSdkVersion", NO_ENTRY, TYPE_INT_DEC, sdk_version),
                    (ANDROID_NS, "targetSdkVersion", NO_ENTRY, TYPE_INT_DEC, sdk_version),
                ],
            ),
            end_elem("uses-sdk"),
            start_elem(
                "overlay",
                [
                    (ANDROID_NS, "priority", NO_ENTRY, TYPE_INT_DEC, 1),
                    (ANDROID_NS, "targetPackage", idx["android"], TYPE_STRING, idx["android"]),
                    (
                        ANDROID_NS,
                        "category",
                        idx["android.theme.customization.font"],
                        TYPE_STRING,
                        idx["android.theme.customization.font"],
                    ),
                ],
            ),
            end_elem("overlay"),
            start_elem(
                "application",
                [
                    (ANDROID_NS, "label", NO_ENTRY, TYPE_REFERENCE, 0x7F010000),
                    (ANDROID_NS, "hasCode", NO_ENTRY, TYPE_INT_BOOLEAN, 0),
                    (ANDROID_NS, "extractNativeLibs", NO_ENTRY, TYPE_INT_BOOLEAN, -1),
                ],
            ),
            end_elem("application"),
            end_elem("manifest"),
            end_ns(),
        ]
    )

    total_size = 8 + len(str_pool) + len(res_map) + len(body)
    return struct.pack("<HHI", RES_XML_TYPE, 8, total_size) + str_pool + res_map + body


def write_aligned_apk(out_path: Path, arsc: bytes, manifest: bytes) -> None:
    """Write an unsigned APK with resources.arsc uncompressed and 4-byte aligned."""
    with zipfile.ZipFile(out_path, "w") as zf:
        # Place resources.arsc first; compute exact local-header offset padding so its
        # data begins on a 4-byte boundary (required by Android 11+ PackageParser).
        zi_arsc = zipfile.ZipInfo("resources.arsc", date_time=(2009, 1, 1, 0, 0, 0))
        zi_arsc.compress_type = zipfile.ZIP_STORED
        # Local file header is 30 bytes + len(filename) + len(extra).
        # Data offset = 30 + len("resources.arsc") = 44 -> already 4-byte aligned!
        base_off = 30 + len(zi_arsc.filename.encode("utf-8"))
        pad = (4 - (base_off % 4)) % 4
        if pad:
            zi_arsc.extra = b"\x00" * pad
        zf.writestr(zi_arsc, arsc)

        zi_man = zipfile.ZipInfo("AndroidManifest.xml", date_time=(2009, 1, 1, 0, 0, 0))
        zi_man.compress_type = zipfile.ZIP_DEFLATED
        zf.writestr(zi_man, manifest)


def main() -> None:
    p = argparse.ArgumentParser(description="Build an unsigned Android font RRO overlay APK.")
    p.add_argument("--package", required=True, help="Overlay package name (com.android.theme.font.*)")
    p.add_argument("--label", required=True, help="Human-readable label shown in Personalize -> Fonts")
    p.add_argument("--family", required=True, help="Font family name from fonts_customization.xml")
    p.add_argument("--time-family", help="Optional clock/time font family (defaults to --family)")
    p.add_argument("--out", required=True, type=Path, help="Output unsigned APK path")
    args = p.parse_args()

    arsc = build_arsc(
        package_name=args.package,
        app_label=args.label,
        body_family=args.family,
        time_family=args.time_family or args.family,
    )
    manifest = build_manifest_axml(package_name=args.package)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    write_aligned_apk(args.out, arsc, manifest)


if __name__ == "__main__":
    main()

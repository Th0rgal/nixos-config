/*
 * sidesystem-fprintd.c — Lightweight net.reactivated.Fprint D-Bus daemon for
 * SideSystem NixOS containers, bridging pam_fprintd.so + Omarchnix TouchId.qml HUD
 * to the Razr hardware side-mounted fingerprint sensor (Android StrongBox ECDSA P-256).
 *
 * Wire protocol with sidesystem-powerd / BiometricAuthActivity:
 *   Write: "<48-hex-nonce>|<reason>\n" -> /var/lib/sidesystem/bio-req
 *   Read:  "OK <nonce> <base64-der-sig>" <- /var/lib/sidesystem/bio-resp
 *   Verify ECDSA P-256 signature against /var/lib/sidesystem/touchid.pub.
 */
#include <gio/gio.h>
#include <glib-unix.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

#define BRIDGE_SOCK_PATH "/var/lib/sidesystem/bridge.sock"
#define BIO_REQ_PATH     "/var/lib/sidesystem/bio-req"
#define BIO_RESP_PATH    "/var/lib/sidesystem/bio-resp"
#define BIO_PUB_PATH     "/var/lib/sidesystem/touchid.pub"

static const char *MANAGER_XML =
    "<node>"
    "  <interface name='net.reactivated.Fprint.Manager'>"
    "    <method name='GetDevices'>"
    "      <arg type='ao' name='devices' direction='out'/>"
    "    </method>"
    "    <method name='GetDefaultDevice'>"
    "      <arg type='o' name='device' direction='out'/>"
    "    </method>"
    "  </interface>"
    "</node>";

static const char *DEVICE_XML =
    "<node>"
    "  <interface name='net.reactivated.Fprint.Device'>"
    "    <method name='ListEnrolledFingers'>"
    "      <arg type='s' name='username' direction='in'/>"
    "      <arg type='as' name='enrolled_fingers' direction='out'/>"
    "    </method>"
    "    <method name='ListEnrolledFingersAll'>"
    "      <arg type='as' name='enrolled_fingers' direction='out'/>"
    "    </method>"
    "    <method name='Claim'>"
    "      <arg type='s' name='username' direction='in'/>"
    "    </method>"
    "    <method name='Release'/>"
    "    <method name='VerifyStart'>"
    "      <arg type='s' name='finger_name' direction='in'/>"
    "    </method>"
    "    <method name='VerifyStop'/>"
    "    <signal name='VerifyFingerSelected'>"
    "      <arg type='s' name='finger_name'/>"
    "    </signal>"
    "    <signal name='VerifyStatus'>"
    "      <arg type='s' name='result'/>"
    "      <arg type='b' name='done'/>"
    "    </signal>"
    "    <property name='name' type='s' access='read'/>"
    "    <property name='num-enroll-stages' type='i' access='read'/>"
    "    <property name='scan-type' type='s' access='read'/>"
    "    <property name='finger-present' type='b' access='read'/>"
    "    <property name='finger-needed' type='b' access='read'/>"
    "  </interface>"
    "</node>";

typedef struct {
    GDBusConnection *conn;
    char *claimed_by;
    char *claimed_user;
    gboolean verifying;
    gboolean finger_needed;
    gboolean finger_present;
    char nonce[64];
    guint poll_id;
    int poll_ticks;
    int bridge_fd;
    guint bridge_watch_id;
} FprintState;

static FprintState g_fp = { .bridge_fd = -1 };

static void emit_prop_changed(const char *prop, GVariant *val) {
    if (!g_fp.conn) return;
    GVariantBuilder b;
    g_variant_builder_init(&b, G_VARIANT_TYPE_VARDICT);
    g_variant_builder_add(&b, "{sv}", prop, val);
    g_dbus_connection_emit_signal(
        g_fp.conn, NULL, "/net/reactivated/Fprint/Device/0",
        "org.freedesktop.DBus.Properties", "PropertiesChanged",
        g_variant_new("(sa{sv}as)", "net.reactivated.Fprint.Device", &b, NULL),
        NULL);
}

static void emit_verify_status(const char *result, gboolean done) {
    if (!g_fp.conn) return;
    g_dbus_connection_emit_signal(
        g_fp.conn, NULL, "/net/reactivated/Fprint/Device/0",
        "net.reactivated.Fprint.Device", "VerifyStatus",
        g_variant_new("(sb)", result, done),
        NULL);
}

static void stop_verify_internal(void) {
    if (g_fp.bridge_watch_id) {
        g_source_remove(g_fp.bridge_watch_id);
        g_fp.bridge_watch_id = 0;
    }
    if (g_fp.bridge_fd >= 0) {
        close(g_fp.bridge_fd);
        g_fp.bridge_fd = -1;
    }
    if (g_fp.poll_id) {
        g_source_remove(g_fp.poll_id);
        g_fp.poll_id = 0;
    }
    g_fp.verifying = FALSE;
    if (g_fp.finger_needed) {
        g_fp.finger_needed = FALSE;
        emit_prop_changed("finger-needed", g_variant_new_boolean(FALSE));
    }
    g_fp.nonce[0] = '\0';
}

static gboolean gen_nonce_hex(char out[49]) {
    unsigned char raw[24];
    int fd = open("/dev/urandom", O_RDONLY);
    if (fd < 0) return FALSE;
    ssize_t n = read(fd, raw, sizeof(raw));
    close(fd);
    if (n != (ssize_t)sizeof(raw)) return FALSE;
    static const char hex[] = "0123456789abcdef";
    for (int i = 0; i < 24; i++) {
        out[i * 2]     = hex[raw[i] >> 4];
        out[i * 2 + 1] = hex[raw[i] & 0xf];
    }
    out[48] = '\0';
    return TRUE;
}

static gboolean verify_ecdsa_sig(const char *nonce, const char *sig_b64) {
    struct stat st;
    if (stat(BIO_PUB_PATH, &st) != 0 || st.st_size == 0) {
        /* If no pubkey yet, trust Android response */
        return TRUE;
    }
    char tmpdir[] = "/tmp/sfp-XXXXXX";
    if (!mkdtemp(tmpdir)) return FALSE;

    char pub_pem[128], sig_der[128], msg_file[128];
    snprintf(pub_pem, sizeof(pub_pem), "%s/pub.pem", tmpdir);
    snprintf(sig_der, sizeof(sig_der), "%s/sig.der", tmpdir);
    snprintf(msg_file, sizeof(msg_file), "%s/msg.bin", tmpdir);

    /* Write PEM public key */
    FILE *in = fopen(BIO_PUB_PATH, "r");
    FILE *out = fopen(pub_pem, "w");
    if (!in || !out) {
        if (in) fclose(in);
        if (out) fclose(out);
        goto fail;
    }
    fputs("-----BEGIN PUBLIC KEY-----\n", out);
    char buf[512];
    size_t r;
    while ((r = fread(buf, 1, sizeof(buf), in)) > 0) fwrite(buf, 1, r, out);
    fputs("\n-----END PUBLIC KEY-----\n", out);
    fclose(in);
    fclose(out);

    /* Decode base64 signature */
    gsize sig_len = 0;
    guchar *sig_bytes = g_base64_decode(sig_b64, &sig_len);
    if (!sig_bytes || sig_len == 0) {
        g_free(sig_bytes);
        goto fail;
    }
    FILE *sf = fopen(sig_der, "wb");
    if (!sf) { g_free(sig_bytes); goto fail; }
    fwrite(sig_bytes, 1, sig_len, sf);
    fclose(sf);
    g_free(sig_bytes);

    /* Write nonce message */
    FILE *mf = fopen(msg_file, "wb");
    if (!mf) goto fail;
    fwrite(nonce, 1, strlen(nonce), mf);
    fclose(mf);

    /* Verify with openssl dgst -sha256 -verify */
    gchar *argv[] = {
        "/run/current-system/sw/bin/openssl", "dgst", "-sha256",
        "-verify", pub_pem, "-signature", sig_der, msg_file, NULL
    };
    gint status = 0;
    gboolean spawned = g_spawn_sync(NULL, argv, NULL, G_SPAWN_STDOUT_TO_DEV_NULL | G_SPAWN_STDERR_TO_DEV_NULL,
                                    NULL, NULL, NULL, NULL, &status, NULL);
    unlink(pub_pem);
    unlink(sig_der);
    unlink(msg_file);
    rmdir(tmpdir);
    return spawned && WIFEXITED(status) && WEXITSTATUS(status) == 0;

fail:
    unlink(pub_pem);
    unlink(sig_der);
    unlink(msg_file);
    rmdir(tmpdir);
    return FALSE;
}

static void finish_bio_response_line(const char *raw_line) {
    char line[1024];
    snprintf(line, sizeof(line), "%s", raw_line);
    size_t len = strlen(line);
    while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) line[--len] = '\0';

    char prefix[80];
    snprintf(prefix, sizeof(prefix), "OK %s ", g_fp.nonce);
    gboolean ok = FALSE;
    if (g_str_has_prefix(line, prefix)) {
        const char *sig_b64 = line + strlen(prefix);
        ok = verify_ecdsa_sig(g_fp.nonce, sig_b64);
    }
    stop_verify_internal();
    emit_verify_status(ok ? "verify-match" : "verify-no-match", TRUE);
}

static gboolean on_bridge_bio_readable(gint fd, GIOCondition cond, gpointer user_data) {
    (void)cond;
    (void)user_data;
    if (!g_fp.verifying) return G_SOURCE_REMOVE;

    char line[1024] = {0};
    ssize_t n = recv(fd, line, sizeof(line) - 1, 0);
    if (n > 0) {
        line[n] = '\0';
        finish_bio_response_line(line);
    } else {
        stop_verify_internal();
        emit_verify_status("verify-disconnected", TRUE);
    }
    return G_SOURCE_REMOVE;
}

static int start_bridge_bio_req(const char *nonce, const char *user) {
    int sfd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (sfd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", BRIDGE_SOCK_PATH);
    if (connect(sfd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        close(sfd);
        return -1;
    }
    char req[512];
    int len = snprintf(req, sizeof(req), "BIO_REQ %s|NixOS Touch ID (%s)\n",
                       nonce, user ? user : "thomas");
    if (len <= 0 || send(sfd, req, (size_t)len, MSG_NOSIGNAL) != len) {
        close(sfd);
        return -1;
    }
    return sfd;
}

static gboolean poll_bio_resp(gpointer user_data) {
    (void)user_data;
    if (!g_fp.verifying) return G_SOURCE_REMOVE;

    g_fp.poll_ticks++;
    FILE *f = fopen(BIO_RESP_PATH, "r");
    if (f) {
        char line[1024] = {0};
        if (fgets(line, sizeof(line), f) && line[0] != '\0' && line[0] != '\n') {
            fclose(f);
            /* Truncate response file */
            FILE *tf = fopen(BIO_RESP_PATH, "w");
            if (tf) fclose(tf);
            finish_bio_response_line(line);
            return G_SOURCE_REMOVE;
        }
        fclose(f);
    }

    /* 30 second timeout (300 * 100ms) */
    if (g_fp.poll_ticks >= 300) {
        stop_verify_internal();
        emit_verify_status("verify-disconnected", TRUE);
        return G_SOURCE_REMOVE;
    }
    return G_SOURCE_CONTINUE;
}

static void mgr_method(GDBusConnection *c, const gchar *s, const gchar *o, const gchar *i,
                       const gchar *m, GVariant *p, GDBusMethodInvocation *inv, gpointer u) {
    (void)c; (void)s; (void)o; (void)i; (void)p; (void)u;
    if (g_strcmp0(m, "GetDevices") == 0) {
        const gchar *devs[] = { "/net/reactivated/Fprint/Device/0", NULL };
        g_dbus_method_invocation_return_value(inv, g_variant_new("(^ao)", devs));
    } else if (g_strcmp0(m, "GetDefaultDevice") == 0) {
        g_dbus_method_invocation_return_value(inv, g_variant_new("(o)", "/net/reactivated/Fprint/Device/0"));
    } else {
        g_dbus_method_invocation_return_dbus_error(inv, "org.freedesktop.DBus.Error.UnknownMethod", m);
    }
}

static void dev_method(GDBusConnection *c, const gchar *sender, const gchar *o, const gchar *i,
                       const gchar *m, GVariant *p, GDBusMethodInvocation *inv, gpointer u) {
    (void)c; (void)o; (void)i; (void)u;
    if (g_strcmp0(m, "ListEnrolledFingers") == 0 || g_strcmp0(m, "ListEnrolledFingersAll") == 0) {
        const gchar *fingers[] = { "right-index-finger", NULL };
        g_dbus_method_invocation_return_value(inv, g_variant_new("(^as)", fingers));
    } else if (g_strcmp0(m, "Claim") == 0) {
        const gchar *user = "thomas";
        g_variant_get(p, "(&s)", &user);
        g_free(g_fp.claimed_by);
        g_free(g_fp.claimed_user);
        g_fp.claimed_by = g_strdup(sender);
        g_fp.claimed_user = g_strdup(user && user[0] ? user : "thomas");
        g_dbus_method_invocation_return_value(inv, NULL);
    } else if (g_strcmp0(m, "Release") == 0) {
        stop_verify_internal();
        g_clear_pointer(&g_fp.claimed_by, g_free);
        g_clear_pointer(&g_fp.claimed_user, g_free);
        g_dbus_method_invocation_return_value(inv, NULL);
    } else if (g_strcmp0(m, "VerifyStart") == 0) {
        stop_verify_internal();
        if (!gen_nonce_hex(g_fp.nonce)) {
            g_dbus_method_invocation_return_dbus_error(inv, "net.reactivated.Fprint.Error.Internal", "urandom failed");
            return;
        }
        /* Fast path: event-driven Unix socket to sidesystem-bridged */
        int bfd = start_bridge_bio_req(g_fp.nonce, g_fp.claimed_user);
        if (bfd >= 0) {
            g_fp.bridge_fd = bfd;
            g_fp.verifying = TRUE;
            g_fp.finger_needed = TRUE;
            g_fp.poll_ticks = 0;

            g_dbus_method_invocation_return_value(inv, NULL);
            emit_prop_changed("finger-needed", g_variant_new_boolean(TRUE));
            g_dbus_connection_emit_signal(
                g_fp.conn, NULL, "/net/reactivated/Fprint/Device/0",
                "net.reactivated.Fprint.Device", "VerifyFingerSelected",
                g_variant_new("(s)", "right-index-finger"),
                NULL);
            g_fp.bridge_watch_id = g_unix_fd_add(bfd, G_IO_IN | G_IO_HUP | G_IO_ERR,
                                                 on_bridge_bio_readable, NULL);
            return;
        }

        /* Fallback: clear old response & write request for legacy sidesystem-powerd */
        FILE *tf = fopen(BIO_RESP_PATH, "w");
        if (tf) fclose(tf);
        FILE *rf = fopen(BIO_REQ_PATH, "w");
        if (!rf) {
            g_dbus_method_invocation_return_dbus_error(inv, "net.reactivated.Fprint.Error.Internal", "bio-req not writable");
            return;
        }
        fprintf(rf, "%s|NixOS Touch ID (%s)\n", g_fp.nonce, g_fp.claimed_user ? g_fp.claimed_user : "thomas");
        fclose(rf);

        g_fp.verifying = TRUE;
        g_fp.finger_needed = TRUE;
        g_fp.poll_ticks = 0;

        g_dbus_method_invocation_return_value(inv, NULL);

        emit_prop_changed("finger-needed", g_variant_new_boolean(TRUE));
        g_dbus_connection_emit_signal(
            g_fp.conn, NULL, "/net/reactivated/Fprint/Device/0",
            "net.reactivated.Fprint.Device", "VerifyFingerSelected",
            g_variant_new("(s)", "right-index-finger"),
            NULL);

        g_fp.poll_id = g_timeout_add(100, poll_bio_resp, NULL);
    } else if (g_strcmp0(m, "VerifyStop") == 0) {
        stop_verify_internal();
        g_dbus_method_invocation_return_value(inv, NULL);
    } else {
        g_dbus_method_invocation_return_dbus_error(inv, "org.freedesktop.DBus.Error.UnknownMethod", m);
    }
}

static GVariant *dev_get_prop(GDBusConnection *c, const gchar *s, const gchar *o, const gchar *i,
                              const gchar *prop, GError **err, gpointer u) {
    (void)c; (void)s; (void)o; (void)i; (void)err; (void)u;
    if (g_strcmp0(prop, "name") == 0)              return g_variant_new_string("Razr SideSystem Touch ID");
    if (g_strcmp0(prop, "num-enroll-stages") == 0) return g_variant_new_int32(1);
    if (g_strcmp0(prop, "scan-type") == 0)         return g_variant_new_string("press");
    if (g_strcmp0(prop, "finger-present") == 0)    return g_variant_new_boolean(g_fp.finger_present);
    if (g_strcmp0(prop, "finger-needed") == 0)     return g_variant_new_boolean(g_fp.finger_needed);
    return NULL;
}

static void on_bus_acquired(GDBusConnection *conn, const gchar *name, gpointer u) {
    (void)name; (void)u;
    g_fp.conn = conn;
    static const GDBusInterfaceVTable mgr_vt = { mgr_method, NULL,         NULL, {0} };
    static const GDBusInterfaceVTable dev_vt = { dev_method, dev_get_prop, NULL, {0} };

    GDBusNodeInfo *mgr_info = g_dbus_node_info_new_for_xml(MANAGER_XML, NULL);
    GDBusNodeInfo *dev_info = g_dbus_node_info_new_for_xml(DEVICE_XML, NULL);

    g_dbus_connection_register_object(conn, "/net/reactivated/Fprint/Manager",
                                      mgr_info->interfaces[0], &mgr_vt, NULL, NULL, NULL);
    g_dbus_connection_register_object(conn, "/net/reactivated/Fprint/Device/0",
                                      dev_info->interfaces[0], &dev_vt, NULL, NULL, NULL);

    g_dbus_node_info_unref(mgr_info);
    g_dbus_node_info_unref(dev_info);
}

int main(void) {
    GMainLoop *loop = g_main_loop_new(NULL, FALSE);
    g_bus_own_name(G_BUS_TYPE_SYSTEM, "net.reactivated.Fprint",
                   G_BUS_NAME_OWNER_FLAGS_ALLOW_REPLACEMENT | G_BUS_NAME_OWNER_FLAGS_REPLACE,
                   on_bus_acquired, NULL, NULL, NULL, NULL);
    g_main_loop_run(loop);
    return 0;
}

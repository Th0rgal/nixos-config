{
  symlinkJoin,
  code-cursor,
  desktop-file-utils,
}:
symlinkJoin {
  name = "cursor-system-${code-cursor.version}";
  paths = [ code-cursor ];
  nativeBuildInputs = [ desktop-file-utils ];
  postBuild = ''
    entry=$out/share/applications/cursor.desktop
    # The Linux package adds its workspace type as a second MimeType key.
    sed '/^MimeType=application\/x-cursor-workspace;$/d' "$entry" > "$TMPDIR/cursor.desktop"
    desktop-file-edit --add-mime-type=application/x-cursor-workspace "$TMPDIR/cursor.desktop"
    mv -fT "$TMPDIR/cursor.desktop" "$entry"
    desktop-file-validate "$entry"
  '';
}

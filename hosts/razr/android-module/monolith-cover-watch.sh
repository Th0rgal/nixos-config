#!/system/bin/sh
# Zero-polling kernel logd watcher: Motorola's services.jar hardcodes
# pkg=com.motorola.launcher.secondarydisplay in getSecondaryHomeIntent(),
# bypassing Android's config_secondaryHomePackage. Redirect Display #1
# instantaneously to MonolithCoverActivity as uid 0 whenever Motorola's
# SecondaryDisplayLauncher is invoked.

PIDFILE=/dev/monolith-cover-watch.pid
if [ -f "$PIDFILE" ]; then
  OLD_PID="$(cat "$PIDFILE" 2>/dev/null)"
  if [ -n "$OLD_PID" ] && [ "$OLD_PID" != "$$" ]; then
    kill "$OLD_PID" 2>/dev/null || true
  fi
fi
echo "$$" > "$PIDFILE"

while true; do
  logcat -T 1 -s ActivityTaskManager:I | while IFS= read -r line; do
    case "$line" in
      *"cmp=com.motorola.launcher.secondarydisplay/.SecondaryDisplayLauncher"*)
        cmd activity start-activity --display 1 -f 0x10010000 -n de.mm20.launcher2.debug/de.mm20.launcher2.ui.starship.MonolithCoverActivity >/dev/null 2>&1
        ;;
    esac
  done
  sleep 1
done

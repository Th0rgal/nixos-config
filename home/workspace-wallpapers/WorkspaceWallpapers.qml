import Quickshell
import Quickshell.Io
import Quickshell.Wayland
import Quickshell.Hyprland
import QtQuick

Item {
  id: root

  property var shell: null
  property var manifest: null

  readonly property string home: Quickshell.env("HOME")
  readonly property string configPath: home + "/.config/omarchy/workspace-wallpapers.json"
  readonly property string backgroundLink: home + "/.local/state/omarchy/current/background"

  property string defaultWallpaper: home + "/Pictures/Wallpapers/bologna-dome.webp"
  property var workspaceMap: ({
    "1": home + "/Pictures/Wallpapers/singapore.webp",
    "2": home + "/Pictures/Wallpapers/bologna-dome.webp",
    "3": home + "/Pictures/Wallpapers/kazakhstan-charyn-canyon-peaks.webp",
    "4": home + "/Pictures/Wallpapers/shanghai-tower-twilight-spiral.webp",
    "5": home + "/Pictures/Wallpapers/zambezi-violet-twilight.webp",
    "6": home + "/Pictures/Wallpapers/spiral-brick-vault-archway.webp",
    "7": home + "/Pictures/Wallpapers/lisbon-greenhouse-canopy-top.webp",
    "8": home + "/Pictures/Wallpapers/lisbon-greenhouse-canopy-bottom.webp"
  })

  readonly property var allPaths: {
    var seen = {}
    var list = []
    function add(p) {
      var s = String(p || "").trim()
      if (s && !seen[s]) {
        seen[s] = true
        list.push(s)
      }
    }
    var map = root.workspaceMap || {}
    for (var k in map) add(map[k])
    add(root.defaultWallpaper)
    return list
  }

  function pathForWorkspace(wsId) {
    var key = String(wsId || "")
    var map = root.workspaceMap || {}
    if (key && map[key]) return String(map[key])
    return root.defaultWallpaper
  }

  function parseConfig(raw) {
    var text = String(raw || "").trim()
    if (!text) return
    try {
      var obj = JSON.parse(text)
      if (obj && typeof obj === "object") {
        if (typeof obj.default === "string" && obj.default.trim() !== "") {
          root.defaultWallpaper = obj.default.trim()
        }
        if (obj.workspaces && typeof obj.workspaces === "object") {
          var nextMap = {}
          for (var k in obj.workspaces) {
            var v = String(obj.workspaces[k] || "").trim()
            if (v) nextMap[String(k)] = v
          }
          root.workspaceMap = nextMap
        }
      }
    } catch (e) {
      console.warn("workspace-wallpapers: failed to parse config: " + e)
    }
  }

  property string linkedBackgroundPath: ""
  property string pendingLinkTarget: ""
  property var polarityMap: ({})
  property string probedPathsKey: ""

  function shouldSyncBackgroundLink(targetPath) {
    var clean = String(targetPath || "").trim()
    if (!clean) return false
    if (!linkedBackgroundPath) return true
    if (clean === linkedBackgroundPath) return false
    var nextPol = polarityMap[clean]
    var curPol = polarityMap[linkedBackgroundPath]
    if (nextPol && curPol) return nextPol !== curPol
    return false
  }

  function syncCurrentBackgroundLink(targetPath, forceSync) {
    var clean = String(targetPath || "").trim()
    if (!clean) return
    if (!forceSync && !shouldSyncBackgroundLink(clean)) return
    pendingLinkTarget = clean
    if (!linkProc.running) startLinkUpdate()
  }

  function startLinkUpdate() {
    var nextTarget = pendingLinkTarget
    if (!nextTarget) return
    pendingLinkTarget = ""
    linkProc.targetWritten = nextTarget
    linkProc.command = ["ln", "-sfn", nextTarget, root.backgroundLink]
    linkProc.running = true
  }

  function refreshPolarities() {
    var paths = root.allPaths || []
    var key = paths.join("\n")
    if (!key || key === root.probedPathsKey) return
    if (polarityProc.running) return
    root.probedPathsKey = key
    polarityProc.pathsSnapshot = paths.slice()
    polarityProc.command = [
      "bash",
      "-c",
      [
        'cur=$(readlink -f -- "$1" 2>/dev/null || true)',
        'printf "LINK\\t%s\\n" "$cur"',
        'shift',
        'add_pol() {',
        '  local p="$1"',
        '  [ -f "$p" ] || return 0',
        '  local d l',
        '  d=$(omarchy-bar-text-color top 28 "#e6edf3" "#0b0e14" --background "$p" 2>/dev/null || echo "#e6edf3")',
        '  l=$(omarchy-bar-text-color top 28 "#1f2328" "#ffffff" --background "$p" 2>/dev/null || echo "#1f2328")',
        '  printf "POL\\t%s\\t%s|%s\\n" "$p" "$d" "$l"',
        '}',
        '[ -n "$cur" ] && add_pol "$cur"',
        'for p in "$@"; do',
        '  [ "$p" = "$cur" ] && continue',
        '  add_pol "$p"',
        'done'
      ].join("\n"),
      "_",
      root.backgroundLink
    ].concat(paths)
    polarityProc.running = true
  }

  onAllPathsChanged: refreshPolarities()

  Component.onCompleted: refreshPolarities()

  Process {
    id: polarityProc
    property var pathsSnapshot: []
    stdout: StdioCollector {
      waitForEnd: true
      onStreamFinished: {
        var lines = String(text || "").split("\n")
        var nextPol = {}
        for (var k in root.polarityMap) nextPol[k] = root.polarityMap[k]
        var detectedLink = root.linkedBackgroundPath
        for (var i = 0; i < lines.length; i++) {
          var line = lines[i]
          if (!line) continue
          var parts = line.split("\t")
          if (parts[0] === "LINK") {
            detectedLink = String(parts[1] || "").trim()
          } else if (parts[0] === "POL" && parts.length >= 3) {
            nextPol[parts[1]] = parts[2]
          }
        }
        root.polarityMap = nextPol
        if (detectedLink) {
          root.linkedBackgroundPath = detectedLink
        }
        var activeTarget = root.pathForWorkspace(
          (Hyprland.focusedMonitor && Hyprland.focusedMonitor.activeWorkspace && Hyprland.focusedMonitor.activeWorkspace.id > 0)
            ? Hyprland.focusedMonitor.activeWorkspace.id
            : 1
        )
        if (!root.linkedBackgroundPath) {
          root.syncCurrentBackgroundLink(activeTarget, true)
        } else if (root.shouldSyncBackgroundLink(activeTarget)) {
          root.syncCurrentBackgroundLink(activeTarget, true)
        }
      }
    }
    onExited: {
      var latestKey = (root.allPaths || []).join("\n")
      if (latestKey !== root.probedPathsKey) root.refreshPolarities()
    }
  }

  Process {
    id: linkProc
    property string targetWritten: ""
    onExited: function(exitCode) {
      if (exitCode === 0 && linkProc.targetWritten !== "") {
        root.linkedBackgroundPath = linkProc.targetWritten
      }
      if (root.pendingLinkTarget !== "") root.startLinkUpdate()
    }
  }

  FileView {
    id: configFile
    path: root.configPath
    watchChanges: true
    printErrors: false
    onLoaded: root.parseConfig(text())
    onFileChanged: reload()
  }

  Variants {
    model: Quickshell.screens

    PanelWindow {
      id: panel
      required property var modelData

      screen: modelData
      visible: true
      color: "#0b0e14"
      updatesEnabled: true
      anchors { top: true; bottom: true; left: true; right: true }
      exclusionMode: ExclusionMode.Ignore

      WlrLayershell.namespace: "omarchnix-workspace-wallpaper"
      WlrLayershell.layer: WlrLayer.Background
      WlrLayershell.keyboardFocus: WlrKeyboardFocus.None

      readonly property HyprlandMonitor monitor: Hyprland.monitorFor(screen)
      readonly property int activeWsId: (monitor && monitor.activeWorkspace && monitor.activeWorkspace.id > 0 && monitor.activeWorkspace.id < 9000)
        ? monitor.activeWorkspace.id
        : 1
      readonly property string targetPath: root.pathForWorkspace(activeWsId)

      readonly property int decodeWidth: width > 0 ? Math.ceil(width * screen.devicePixelRatio) : 0
      readonly property int decodeHeight: height > 0 ? Math.ceil(height * screen.devicePixelRatio) : 0

      property string currentPath: targetPath
      property string previousPath: targetPath
      property real fadeProgress: 1.0

      Component.onCompleted: {
        currentPath = targetPath
        previousPath = targetPath
        if (!monitor || monitor.focused) root.syncCurrentBackgroundLink(targetPath)
      }

      onTargetPathChanged: {
        if (!targetPath || targetPath === currentPath) return
        previousPath = currentPath || targetPath
        currentPath = targetPath
        fadeAnim.stop()
        fadeProgress = 0
        fadeAnim.start()
        if (!monitor || monitor.focused) root.syncCurrentBackgroundLink(targetPath)
      }

      NumberAnimation {
        id: fadeAnim
        target: panel
        property: "fadeProgress"
        from: 0
        to: 1
        duration: 160
        easing.type: Easing.OutCubic
      }

      Item {
        id: stage
        anchors.fill: parent

        Repeater {
          model: root.allPaths

          Image {
            required property string modelData

            x: 0
            y: 0
            width: stage.width
            height: stage.height
            source: panel.decodeWidth > 0 ? "file://" + modelData : ""
            sourceSize.width: panel.decodeWidth
            sourceSize.height: panel.decodeHeight
            fillMode: Image.PreserveAspectCrop
            horizontalAlignment: Image.AlignHCenter
            verticalAlignment: modelData.indexOf("bologna-dome") >= 0 ? Image.AlignTop : Image.AlignVCenter
            asynchronous: true
            cache: true
            smooth: true
            mipmap: false

            readonly property bool isCurrent: modelData === panel.currentPath
            readonly property bool isPrevious: modelData === panel.previousPath && panel.fadeProgress < 1.0

            visible: isCurrent || isPrevious || status !== Image.Ready
            z: isCurrent ? 2 : (isPrevious ? 1 : 0)
            opacity: isCurrent ? panel.fadeProgress : (isPrevious ? 1.0 : 0.0)
          }
        }
      }

      MouseArea {
        anchors.fill: parent
        acceptedButtons: Qt.LeftButton | Qt.RightButton
        onDoubleClicked: function(mouse) {
          if (mouse.button === Qt.RightButton) {
            Quickshell.execDetached(["omarchy-theme-switcher"])
          } else {
            Quickshell.execDetached(["bash", "-c", "background=$(omarchy-theme-bg-switcher); [[ -n $background ]] && omarchy-theme-bg-set \"$background\""])
          }
          mouse.accepted = true
        }
      }
    }
  }
}

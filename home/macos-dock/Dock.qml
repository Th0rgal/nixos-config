import QtQuick
import Quickshell
import Quickshell.Hyprland
import Quickshell.Io
import qs.Commons
import "DockModel.js" as DockModel

// macOS-style auto-hiding bottom dock showing active apps across all Spaces
// (plus any pinned apps) and jumping to an app's active Space on click.
Item {
  id: root

  property var shell: null
  property var manifest: null

  readonly property string userConfigPath: Quickshell.env("HOME") + "/.config/omarchy/shell.json"
  property var parsedDockConfig: ({})

  FileView {
    id: userConfigFile
    path: root.userConfigPath
    watchChanges: true
    printErrors: false
    onLoaded: root.reloadDockConfig()
    onLoadFailed: function() { root.parsedDockConfig = ({}) }
    onFileChanged: reload()
  }

  function reloadDockConfig() {
    var raw = String(userConfigFile.text() || "").trim()
    if (!raw) {
      root.parsedDockConfig = ({})
      return
    }
    try {
      var parsed = JSON.parse(raw)
      root.parsedDockConfig = parsed && Util.isPlainObject(parsed.dock) ? parsed.dock : ({})
    } catch (e) {
      // Ignore partial writes; FileView will reload on atomic completion.
    }
  }

  readonly property var appLibrary: shell ? shell.appLibrary : null
  readonly property var dockConfig: parsedDockConfig || ({})
  readonly property string position: DockModel.normalizePosition(dockConfig.position)
  readonly property bool vertical: DockModel.isVertical(position)
  // Auto-hide at the screen edge by default (macOS Dock behavior), unless
  // explicitly turned off via `omarchy-dock autohide off` (`autohide: false`).
  readonly property bool autohide: dockConfig.autohide !== false
  readonly property bool allWorkspaces: dockConfig.allWorkspaces !== false
  readonly property bool appsOpened: appGrid.opened

  property var order: []
  property var pendingPins: null
  onDockConfigChanged: pendingPins = null

  readonly property var terminalEntries: {
    var values = DesktopEntries.applications.values
    var out = []
    for (var i = 0; i < values.length; i++) {
      var entry = values[i]
      if (entry.runInTerminal || String(entry.execString || "").indexOf("--app-id=") >= 0)
        out.push({ id: entry.id, execString: entry.execString, command: entry.command, runInTerminal: entry.runInTerminal })
    }
    return out
  }

  function entryForAppId(appId) {
    var entry = DesktopEntries.heuristicLookup(appId)
    if (entry) return entry
    var id = DockModel.claimingEntryId(appId, root.terminalEntries)
    return id ? DesktopEntries.byId(id) : null
  }

  readonly property var items: {
    var appsRevision = DesktopEntries.applications.values.length
    var pinned = (root.pendingPins || DockModel.pinnedIds(dockConfig)).filter(function(id) {
      return !!DesktopEntries.byId(id)
    })

    var windows = []
    var toplevels = Hyprland.toplevels.values
    for (var i = 0; i < toplevels.length; i++) {
      var toplevel = toplevels[i].wayland
      if (!toplevel || !toplevel.appId || toplevel.parent) continue
      var entry = root.entryForAppId(toplevel.appId)
      var workspace = toplevels[i].workspace
      windows.push({
        key: entry ? entry.id : toplevel.appId,
        appId: toplevel.appId,
        title: toplevel.title || "",
        hasEntry: !!entry && !entry.noDisplay,
        noDisplay: !!entry && entry.noDisplay === true,
        workspaceId: workspace ? workspace.id : -1,
        minimized: !!workspace && DockModel.isMinimizedWorkspace(workspace.name),
        address: toplevels[i].address,
        toplevel: toplevel
      })
    }

    return DockModel.orderItems(DockModel.buildItems(pinned, windows), root.order)
  }

  readonly property var itemsByKey: {
    var byKey = {}
    for (var i = 0; i < root.items.length; i++) byKey[root.items[i].key] = root.items[i]
    return byKey
  }

  function itemFor(key) {
    return root.itemsByKey[key] || null
  }

  property alias itemModel: itemModel

  ListModel { id: itemModel }

  onItemsChanged: syncItemModel()

  function shownKeys() {
    return root.items.map(function(item) { return item.key })
  }

  function syncItemModel() {
    var keys = root.items.map(function(item) { return item.key })
    for (var i = 0; i < keys.length; i++) {
      if (i < itemModel.count && itemModel.get(i).key === keys[i]) continue
      var found = -1
      for (var j = i + 1; j < itemModel.count; j++) {
        if (itemModel.get(j).key === keys[i]) { found = j; break }
      }
      if (found >= 0) itemModel.move(found, i, 1)
      else itemModel.insert(i, { key: keys[i] })
    }
    if (itemModel.count > keys.length) itemModel.remove(keys.length, itemModel.count - keys.length)
    root.settleLaunches()
  }

  property var launches: ({})
  readonly property int launchTimeout: 10000

  function isLaunching(key) {
    return root.launches[key] !== undefined
  }

  function settleLaunches() {
    var next = {}
    var changed = false
    var now = Date.now()
    for (var key in root.launches) {
      var launch = root.launches[key]
      var item = root.itemsByKey[key]
      var windows = item ? item.windows.length : 0
      if (windows > launch.windows || now - launch.at > root.launchTimeout) changed = true
      else next[key] = launch
    }
    if (changed) root.launches = next
  }

  Timer {
    interval: 1000
    repeat: true
    running: Object.keys(root.launches).length > 0
    onTriggered: root.settleLaunches()
  }

  function entryFor(item) {
    if (!item) return null
    var byId = DesktopEntries.byId(item.key)
    if (byId) return byId
    return root.entryForAppId(item.key)
  }

  function iconFor(item) {
    var entry = entryFor(item)
    var icon = entry && entry.icon ? entry.icon : (item ? item.key : "")
    return root.appLibrary ? root.appLibrary.iconSource(icon) : Quickshell.iconPath(icon, true)
  }

  function labelFor(item) {
    if (!item) return ""
    var entry = entryFor(item)
    if (entry) {
      var entryTitle = root.appLibrary ? root.appLibrary.entryName(entry) : String(entry.name || "")
      if (entryTitle) return entryTitle
    }
    return DockModel.humanizeAppId(item.key)
  }

  function spacesLabelFor(item) {
    if (!item || !item.windows || item.windows.length === 0) return ""
    return DockModel.workspaceSummary(item.windows)
  }

  function launch(item) {
    var entry = entryFor(item)
    if (!entry) return
    var next = Object.assign({}, root.launches)
    next[item.key] = { at: Date.now(), windows: item.windows.length }
    root.launches = next
    root.launchEntry(entry)
  }

  function launchEntry(entry) {
    if (entry && root.appLibrary) root.appLibrary.launch(entry.id, entry.name)
  }

  function activate(item) {
    if (!item) return
    if (item.windows.length === 0) {
      root.launch(item)
      return
    }
    var monitor = Hyprland.focusedMonitor
    var workspaceId = monitor && monitor.activeWorkspace ? monitor.activeWorkspace.id : -1
    var index = DockModel.pickWindow(item.windows.map(function(window) {
      return { workspaceId: window.workspaceId, minimized: window.minimized, activated: window.toplevel.activated }
    }), workspaceId)
    if (index >= 0 && index < item.windows.length)
      root.bringForward(item.windows[index])
  }

  // Focus the selected window — switching Hyprland to the Space where that
  // window lives and raising it to the top of the stack.
  function bringForward(window) {
    var address = String(window.address || "")
    if (!address) {
      window.toplevel.activate()
      return
    }
    if (address.indexOf("0x") !== 0) address = "0x" + address
    var target = "window = \"address:" + address + "\""
    var steps = []
    var after = []
    if (window.minimized) {
      var monitor = Hyprland.focusedMonitor
      var workspace = monitor && monitor.activeWorkspace ? monitor.activeWorkspace.id : -1
      if (!(workspace >= 1)) return
      steps.push("hl.dsp.window.set_prop({ prop = \"no_anim\", value = \"1\", " + target + " })")
      steps.push("hl.dsp.window.move({ workspace = \"" + workspace + "\", " + target + " })")
      after.push("hl.dsp.window.set_prop({ prop = \"no_anim\", value = \"unset\", " + target + " })")
    }
    steps.push("hl.dsp.focus({ " + target + " })")
    steps.push("hl.dsp.window.alter_zorder({ mode = \"top\", " + target + " })")
    var dispatch = function(step) { return "hyprctl dispatch " + Util.shellQuote(step) }
    var command = steps.map(dispatch).join(" && ")
    if (after.length > 0) command += "; sleep 0.4; " + after.map(dispatch).join("; ")
    Util.execDetached(command)
  }

  function quit(item) {
    if (!item) return
    var windows = item.windows.slice()
    for (var i = 0; i < windows.length; i++) windows[i].toplevel.close()
  }

  function openApps() {
    appGrid.open()
  }

  function closeApps() {
    appGrid.close()
  }

  function toggleApps() {
    appGrid.toggle()
  }

  readonly property var pinnedKeys: root.pendingPins || DockModel.pinnedIds(root.dockConfig)

  function isPinned(key) {
    return root.pinnedKeys.indexOf(key) >= 0
  }

  function placeAt(key, index) {
    if (!key) return
    root.order = DockModel.orderWithKeyAt(root.shownKeys(), key, index)
    var pinned = {}
    root.pinnedKeys.forEach(function(other) { pinned[other] = true })
    pinned[key] = true
    root.savePins(DockModel.pinsInOrder(root.order, pinned, root.pinnedKeys))
  }

  function savePins(pins) {
    root.pendingPins = pins
    if (pins.length === 0) {
      Util.execDetached("jq '.dock.pinned = []' " + Util.shellQuote(root.userConfigPath) + " > " + Util.shellQuote(root.userConfigPath + ".tmp") + " && mv " + Util.shellQuote(root.userConfigPath + ".tmp") + " " + Util.shellQuote(root.userConfigPath))
      return
    }
    Util.execDetached("omarchy-dock pinned " + pins.map(function(id) { return Util.shellQuote(id) }).join(" "))
  }

  function moveItem(fromIndex, toIndex) {
    if (fromIndex === toIndex) return
    var keys = root.shownKeys()
    var key = keys[fromIndex]
    if (!key) return
    root.order = DockModel.orderWithKeyAt(keys, key, toIndex)
    if (root.pinnedKeys.length > 0) {
      var pinned = {}
      root.pinnedKeys.forEach(function(other) { pinned[other] = true })
      if (pinned[key]) root.savePins(DockModel.pinsInOrder(root.order, pinned, root.pinnedKeys))
    }
  }

  function pinAt(key, index) {
    var from = root.shownKeys().indexOf(key)
    root.placeAt(key, from >= 0 && from < index ? index - 1 : index)
  }

  function setPinned(key, pinned) {
    if (!key) return
    if (pinned) {
      root.placeAt(key, root.shownKeys().length)
    } else {
      var remaining = root.pinnedKeys.filter(function(other) { return other !== key })
      root.savePins(remaining)
    }
  }

  property var modes: ({})
  property var pendingModes: []
  property int modeGeneration: 0
  property int modeProcWorkspace: -1
  property int modeProcGeneration: 0

  function isFloating(workspaceId) {
    return root.modes[workspaceId] === "floating"
  }

  function readMode(workspaceId) {
    if (root.allWorkspaces) return
    if (!(workspaceId >= 1) || root.modes[workspaceId] !== undefined) return
    if (root.pendingModes.indexOf(workspaceId) === -1) root.pendingModes.push(workspaceId)
    root.readNextMode()
  }

  function readNextMode() {
    if (modeProc.running || root.pendingModes.length === 0) return
    root.modeProcWorkspace = root.pendingModes.shift()
    root.modeProcGeneration = root.modeGeneration
    modeProc.command = ["omarchy-hyprland-workspace-layout-current", String(root.modeProcWorkspace)]
    modeProc.running = true
  }

  function refresh() {
    root.modes = ({})
    root.pendingModes = []
    root.modeGeneration++
    if (root.allWorkspaces) return
    var monitors = Hyprland.monitors.values
    for (var i = 0; i < monitors.length; i++) {
      if (monitors[i].activeWorkspace) root.readMode(monitors[i].activeWorkspace.id)
    }
    var workspaces = Hyprland.workspaces.values
    for (var j = 0; j < workspaces.length; j++) root.readMode(workspaces[j].id)
  }

  Component.onCompleted: {
    reloadDockConfig()
    refresh()
    syncItemModel()
  }

  Process {
    id: modeProc
    stdout: StdioCollector {
      onStreamFinished: {
        var mode = text.trim()
        if (!mode || root.modeProcGeneration !== root.modeGeneration) return
        var next = Object.assign({}, root.modes)
        next[root.modeProcWorkspace] = mode
        root.modes = next
      }
    }
    onExited: Qt.callLater(root.readNextMode)
  }

  Connections {
    target: Hyprland
    function onRawEvent(event) {
      var name = event ? String(event.name) : ""
      if (name === "configreloaded") root.refresh()
      else if (name === "fullscreen" || name === "workspace" || name === "workspacev2" || name === "movewindow" || name === "movewindowv2" || name === "activewindow" || name === "activewindowv2") {
        Hyprland.refreshWorkspaces()
        Hyprland.refreshToplevels()
      }
    }
  }

  ShellIpc {
    target: "th0rgal.macos-dock"

    function refresh(): void { root.refresh() }
    function apps(): void { appGrid.toggle() }
  }

  function dockOn(screen) {
    var windows = docks.instances
    for (var i = 0; i < windows.length; i++) {
      if (windows[i].screen === screen) return windows[i]
    }
    return null
  }

  function moveExternalDrag(screen, x, y) {
    var windows = docks.instances
    for (var i = 0; i < windows.length; i++) {
      if (windows[i].screen === screen) windows[i].moveExternalDrag(x, y)
      else windows[i].endExternalDrag()
    }
  }

  function dropExternalDrag(screen, x, y, key) {
    var target = root.dockOn(screen)
    if (target) target.dropExternalDrag(x, y, key)
    root.endExternalDrag()
  }

  function endExternalDrag() {
    var windows = docks.instances
    for (var i = 0; i < windows.length; i++) windows[i].endExternalDrag()
  }

  Variants {
    id: docks
    model: Quickshell.screens

    delegate: Component {
      DockWindow {
        required property var modelData

        screen: modelData
        dock: root
      }
    }
  }

  AppGrid {
    id: appGrid
    appLibrary: root.appLibrary
    dock: root
  }
}

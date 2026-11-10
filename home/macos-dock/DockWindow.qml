import QtQuick
import Quickshell
import Quickshell.Hyprland
import Quickshell.Wayland
import qs.Commons
import qs.Commons as Commons
import qs.Ui
import "DockModel.js" as DockModel

// Per-screen macOS-style frosted glass dock window. Stays mapped with a thin
// screen-edge hover strip while tucked away so revealing on bottom-edge cursor
// contact is instantaneous and clicks pass straight through elsewhere.
PanelWindow {
  id: window
  Binding { target: window.contentItem; property: "LayoutMirroring.enabled"; value: Commons.I18n.rightToLeft }
  Binding { target: window.contentItem; property: "LayoutMirroring.childrenInherit"; value: true }

  property var dock: null

  readonly property string position: dock ? dock.position : "bottom"
  readonly property bool vertical: dock ? dock.vertical : false
  readonly property var monitor: Hyprland.monitorFor(screen)
  readonly property var workspace: monitor ? monitor.activeWorkspace : null
  readonly property int workspaceId: workspace ? workspace.id : -1

  readonly property int fullscreenMode: {
    var mode = 0
    var toplevels = Hyprland.toplevels.values
    for (var i = 0; i < toplevels.length; i++) {
      var toplevel = toplevels[i]
      if (!toplevel.workspace || toplevel.workspace.id !== workspaceId) continue
      var ipc = toplevel.lastIpcObject
      mode = Math.max(mode, ipc && ipc.fullscreen ? ipc.fullscreen : 0)
    }
    return mode
  }

  readonly property bool shown: !!dock
    && workspaceId >= 1
    && fullscreenMode < 2
    && (dock.allWorkspaces || dock.isFloating(workspaceId))

  readonly property bool autohide: !!dock && (dock.autohide || fullscreenMode === 1)
  property bool revealed: false
  readonly property bool menuOpen: menuItem !== null || settingsMenu
  readonly property bool tucked: autohide && !(revealed || menuOpen || dragging || insertIndex >= 0)
  readonly property bool contentShown: shown && !tucked

  readonly property int gap: Style.spacing.sm
  readonly property int pillPadding: Style.spacing.xs
  readonly property int iconSize: Math.round(40 * Style.fontScale)
  readonly property int iconInset: Style.spacing.sm
  readonly property int slotSize: iconSize + iconInset * 2 + 4
  readonly property real idleOpacity: 0.68
  readonly property int popupRoom: Style.spacing.popupRowHeight * 5 + Style.spacing.sm * 2 + Style.normalBorderWidth * 2 + gap * 3
  readonly property int popOrigin: position === "top" ? Item.Top
    : position === "left" ? Item.Left
    : position === "right" ? Item.Right
    : Item.Bottom

  property Item hoveredSlot: null
  property string hoveredTitle: ""
  property string hoveredSubtitle: ""

  property int dragIndex: -1
  property int dropIndex: -1
  property real dragOffset: 0
  readonly property bool dragging: dragIndex >= 0
  property bool animateShifts: true
  property bool hoverStale: false
  property point dropPoint
  property int insertIndex: -1

  function origin() {
    var reserved = monitor && monitor.lastIpcObject && monitor.lastIpcObject.reserved ? monitor.lastIpcObject.reserved : [0, 0, 0, 0]
    var left = reserved[0] || 0, top = reserved[1] || 0, right = reserved[2] || 0, bottom = reserved[3] || 0
    var x = window.position === "right" ? screen.width - right - window.width : left
    var y = window.position === "bottom" ? screen.height - bottom - window.height : top
    return Qt.point(x, y)
  }

  function moveExternalDrag(screenX, screenY) {
    var at = window.origin()
    var point = Qt.point(screenX - at.x - body.x - window.pillPadding, screenY - at.y - body.y - window.pillPadding)
    var reach = window.slotSize
    var along = window.vertical ? point.y : point.x
    var across = window.vertical ? point.x : point.y
    var length = window.vertical ? body.height : body.width
    var depth = window.vertical ? body.width : body.height
    var near = along >= -window.slotSize && along <= length + window.slotSize && across >= -reach && across <= depth + reach
    if (window.shown && window.autohide && near) window.revealed = true
    window.insertIndex = window.shown && near ? window.slotIndexAt(point) : -1
  }

  function dropExternalDrag(screenX, screenY, key) {
    window.moveExternalDrag(screenX, screenY)
    window.settleShifts()
    if (window.insertIndex >= 0 && key && window.dock) window.dock.pinAt(key, window.insertIndex)
    window.endExternalDrag()
  }

  function endExternalDrag() {
    window.insertIndex = -1
    if (window.autohide) hideTimer.restart()
  }

  readonly property int separatorExtent: (window.dock && window.dock.itemModel.count > 0) ? 13 : 0

  function slotIndexAt(point) {
    var along = window.vertical ? point.y : point.x
    var first = window.slotSize + window.separatorExtent
    var count = window.dock ? window.dock.itemModel.count : 0
    return Math.max(0, Math.min(count, Math.round((along - first) / window.slotSize)))
  }

  property var menuItem: null
  property Item menuSlot: null
  property bool settingsMenu: false

  onWorkspaceIdChanged: if (dock) dock.readMode(workspaceId)
  onShownChanged: {
    if (shown) return
    closeMenu()
    window.revealed = false
    window.hoveredSlot = null
  }
  Component.onCompleted: if (dock) dock.readMode(workspaceId)

  function openMenu(item, slot) {
    window.settingsMenu = false
    window.menuItem = item
    window.menuSlot = slot
  }

  function openSettingsMenu(slot) {
    window.menuItem = null
    window.settingsMenu = true
    window.menuSlot = slot
  }

  function settleShifts() {
    window.animateShifts = false
    Qt.callLater(function() { window.animateShifts = true })
  }

  function endDrag() {
    window.settleShifts()
    window.hoverStale = true
    window.dragIndex = -1
    window.dropIndex = -1
    window.dragOffset = 0
  }

  function closeMenu() {
    window.menuItem = null
    window.settingsMenu = false
    window.menuSlot = null
    if (window.autohide) hideTimer.restart()
  }

  function popupX(slot, popup) {
    if (!slot) return 0
    var center = slot.mapToItem(window.contentItem, slot.width / 2, 0).x
    if (window.position === "left") return body.x + body.width + window.gap
    if (window.position === "right") return body.x - window.gap - popup.width
    return Math.max(window.gap, Math.min(window.width - popup.width - window.gap, Math.round(center - popup.width / 2)))
  }

  function popupY(slot, popup) {
    if (!slot) return 0
    var center = slot.mapToItem(window.contentItem, 0, slot.height / 2).y
    if (window.position === "top") return body.y + body.height + window.gap
    if (window.position === "bottom") return body.y - window.gap - popup.height
    return Math.max(window.gap, Math.min(window.height - popup.height - window.gap, Math.round(center - popup.height / 2)))
  }

  color: "transparent"
  // Matches Hyprland's built-in `omarchy-glass` Kawase blur layer_rule.
  WlrLayershell.namespace: "omarchy-dock"
  WlrLayershell.layer: WlrLayer.Top
  WlrLayershell.keyboardFocus: WlrKeyboardFocus.None
  exclusionMode: ExclusionMode.Normal
  exclusiveZone: 0

  anchors {
    top: window.position !== "bottom"
    bottom: window.position !== "top"
    left: window.position !== "right"
    right: window.position !== "left"
  }

  implicitWidth: window.vertical ? window.gap + window.slotSize + window.pillPadding * 2 + window.popupRoom : 0
  implicitHeight: window.vertical ? 0 : window.gap + window.slotSize + window.pillPadding * 2 + window.popupRoom

  mask: Region {
    item: window.contentShown ? body : null

    Region { item: window.shown && window.autohide ? edgeTrigger : null }
    Region { item: menu.visible ? menu : null }
  }

  onAutohideChanged: window.revealed = false

  Timer {
    id: hideTimer
    interval: 180
    onTriggered: {
      if (!bodyHover.hovered && !edgeHover.hovered && !window.menuOpen && !window.dragging) {
        window.revealed = false
        window.hoveredSlot = null
      }
    }
  }

  // Broad macOS-style screen-edge strip (72% of the screen edge, centered) so
  // moving the cursor to the bottom of the screen reveals the dock naturally
  // without hunting for a narrow target, while keeping extreme corners free.
  Item {
    id: edgeTrigger

    readonly property int depth: 5
    readonly property int spanLength: window.vertical
      ? Math.min(window.height, Math.max(body.height + window.slotSize * 2, Math.round(window.height * 0.72)))
      : Math.min(window.width, Math.max(body.width + window.slotSize * 2, Math.round(window.width * 0.72)))

    x: window.position === "left" ? 0
      : window.position === "right" ? window.width - depth
      : Math.round((window.width - spanLength) / 2)
    y: window.position === "top" ? 0
      : window.position === "bottom" ? window.height - depth
      : Math.round((window.height - spanLength) / 2)
    width: window.vertical ? depth : spanLength
    height: window.vertical ? spanLength : depth

    HoverHandler {
      id: edgeHover
      onHoveredChanged: {
        if (hovered) window.revealed = true
        else hideTimer.restart()
      }
    }
  }

  HyprlandFocusGrab {
    active: window.menuOpen
    windows: [window]
    onCleared: window.closeMenu()
  }

  BorderSurface {
    id: body

    LayoutMirroring.enabled: false
    LayoutMirroring.childrenInherit: true

    width: slotsFlow.implicitWidth + window.pillPadding * 2
    height: slotsFlow.implicitHeight + window.pillPadding * 2
    radius: Style.cornerRadius + 8
    color: Qt.rgba(
      Commons.Color.bar.background.r,
      Commons.Color.bar.background.g,
      Commons.Color.bar.background.b,
      0.56
    )
    borderSpec: Border.flat(Qt.rgba(1, 1, 1, 0.14), 1)

    property real tuck: window.tucked ? (window.vertical ? width : height) + window.gap + 4 : 0

    Behavior on tuck {
      id: tuckMotion
      enabled: window.shown
      NumberAnimation {
        duration: Style.duration(tuckMotion.targetValue > 0 ? 140 : 190)
        easing.type: Easing.OutCubic
      }
    }

    x: window.position === "left" ? window.gap - tuck
      : window.position === "right" ? window.width - width - window.gap + tuck
      : Math.round((window.width - width) / 2)
    y: window.position === "top" ? window.gap - tuck
      : window.position === "bottom" ? window.height - height - window.gap + tuck
      : Math.round((window.height - height) / 2)

    opacity: window.shown ? 1 : 0

    HoverHandler {
      id: bodyHover
      onHoveredChanged: {
        if (!hovered) {
          window.hoveredSlot = null
          if (window.autohide) hideTimer.restart()
        }
      }
      onPointChanged: {
        if (!window.hoverStale || window.dragging) return
        var at = point.position
        if (Math.hypot(at.x - window.dropPoint.x, at.y - window.dropPoint.y) > Style.space(4)) window.hoverStale = false
      }
    }

    Grid {
      id: slotsFlow
      anchors.centerIn: parent
      columns: window.vertical ? 1 : (1 + (dockSeparator.visible ? 1 : 0) + appRepeater.count)

      AppsSlot {}

      Item {
        id: dockSeparator
        visible: appRepeater.count > 0
        width: window.vertical ? window.slotSize : 13
        height: window.vertical ? 13 : window.slotSize

        Rectangle {
          anchors.centerIn: parent
          width: window.vertical ? Math.round(window.iconSize * 0.68) : 1
          height: window.vertical ? 1 : Math.round(window.iconSize * 0.68)
          color: Qt.rgba(1, 1, 1, 0.14)
        }
      }

      Repeater {
        id: appRepeater
        model: window.dock ? window.dock.itemModel : null

        delegate: AppSlot {}
      }
    }
  }

  // Floating macOS-style label pill above the hovered dock icon showing the
  // app's name and which Space(s) it is active on.
  BorderSurface {
    id: hoverTooltip

    readonly property bool active: window.contentShown
      && !window.menuOpen
      && !window.dragging
      && window.hoveredSlot !== null
      && window.hoveredTitle !== ""

    visible: opacity > 0.01
    opacity: active ? 1 : 0
    Behavior on opacity { NumberAnimation { duration: Style.duration(90); easing.type: Easing.OutCubic } }

    width: tooltipRow.implicitWidth + Style.spacing.md * 2
    height: tooltipRow.implicitHeight + Style.spacing.xs * 2
    x: window.popupX(window.hoveredSlot, hoverTooltip)
    y: window.popupY(window.hoveredSlot, hoverTooltip)
    radius: Math.max(6, Math.round(Style.cornerRadius * 0.8))
    color: Qt.rgba(
      Commons.Color.popups.background.r,
      Commons.Color.popups.background.g,
      Commons.Color.popups.background.b,
      0.92
    )
    borderSpec: Border.flat(Qt.rgba(1, 1, 1, 0.14), 1)

    Row {
      id: tooltipRow
      anchors.centerIn: parent
      spacing: Style.spacing.xs

      Text {
        anchors.verticalCenter: parent.verticalCenter
        textFormat: Text.PlainText
        text: window.hoveredTitle
        color: Commons.Color.popups.text
        font.family: Style.font.menuFamily
        font.pixelSize: Style.font.body
        font.weight: Font.Medium
      }

      Text {
        visible: window.hoveredSubtitle !== ""
        anchors.verticalCenter: parent.verticalCenter
        textFormat: Text.PlainText
        text: "· " + window.hoveredSubtitle
        color: Commons.Color.popups.text
        opacity: 0.62
        font.family: Style.font.menuFamily
        font.pixelSize: Math.max(10, Style.font.body - 1)
      }
    }
  }

  component Slot: Item {
    id: slot

    property bool hovered: mouse.containsMouse && !window.hoverStale && !window.dragging
    property bool pressed: mouse.pressed
    property alias mouse: mouse
    property string tooltipTitle: ""
    property string tooltipSubtitle: ""

    width: window.slotSize
    height: window.slotSize

    onHoveredChanged: {
      if (hovered) {
        window.hoveredSlot = slot
        window.hoveredTitle = slot.tooltipTitle
        window.hoveredSubtitle = slot.tooltipSubtitle
      } else if (window.hoveredSlot === slot) {
        window.hoveredSlot = null
      }
    }

    onTooltipTitleChanged: if (window.hoveredSlot === slot) window.hoveredTitle = tooltipTitle
    onTooltipSubtitleChanged: if (window.hoveredSlot === slot) window.hoveredSubtitle = tooltipSubtitle

    MouseArea {
      id: mouse
      anchors.fill: parent
      hoverEnabled: true
      cursorShape: Qt.PointingHandCursor
      acceptedButtons: Qt.LeftButton | Qt.MiddleButton | Qt.RightButton
    }
  }

  component AppsSlot: Slot {
    id: appsSlot

    tooltipTitle: "Applications"
    tooltipSubtitle: ""

    property real displayedShift: window.insertIndex >= 0 ? -window.slotSize / 2 : 0

    Behavior on displayedShift {
      enabled: window.animateShifts
      NumberAnimation { duration: Style.duration(150); easing.type: Easing.Bezier; easing.bezierCurve: Style.easeOut }
    }

    transform: Translate {
      x: window.vertical ? 0 : appsSlot.displayedShift
      y: window.vertical ? appsSlot.displayedShift : 0
    }

    mouse.onClicked: function(event) {
      if (event.button === Qt.RightButton) {
        window.openSettingsMenu(appsSlot)
        return
      }
      window.closeMenu()
      if (window.dock) window.dock.openApps()
    }

    Item {
      x: window.iconInset
      y: window.iconInset
      width: window.iconSize
      height: window.iconSize
      scale: appsSlot.hovered ? 1.16 : 1
      transformOrigin: window.popOrigin

      Behavior on scale { NumberAnimation { duration: Style.duration(120); easing.type: Easing.OutCubic } }

      Grid {
        readonly property int square: Math.round(window.iconSize * 0.21)

        anchors.centerIn: parent
        columns: 3
        spacing: Math.round(window.iconSize * 0.09)

        Repeater {
          model: 9

          delegate: Rectangle {
            width: parent.square
            height: parent.square
            radius: Math.max(1, Math.round(parent.square * 0.25))
            color: Commons.Color.bar.text
            opacity: 0.9
          }
        }
      }
    }
  }

  component AppSlot: Slot {
    id: appSlot

    required property int index
    required property string key
    readonly property var modelData: window.dock ? window.dock.itemFor(key) : null
    readonly property bool launching: !!window.dock && window.dock.isLaunching(key)
    readonly property bool lifted: window.dragIndex === index
    property point pressPoint
    property bool dragged: false
    readonly property int windowCount: modelData ? modelData.windows.length : 0
    readonly property bool focusedApp: {
      if (!modelData || !modelData.windows) return false
      for (var i = 0; i < modelData.windows.length; i++) {
        if (modelData.windows[i].toplevel && modelData.windows[i].toplevel.activated)
          return true
      }
      return false
    }
    property real pulse: 1

    tooltipTitle: window.dock && modelData ? window.dock.labelFor(modelData) : ""
    tooltipSubtitle: window.dock && modelData ? window.dock.spacesLabelFor(modelData) : ""

    readonly property real shift: window.insertIndex >= 0 ? (index >= window.insertIndex ? 1 : -1) * window.slotSize / 2
      : !window.dragging ? 0
      : lifted ? window.dragOffset
      : (window.dragIndex < index && index <= window.dropIndex) ? -window.slotSize
      : (window.dropIndex <= index && index < window.dragIndex) ? window.slotSize
      : 0
    property real displayedShift: shift

    Behavior on displayedShift {
      enabled: window.animateShifts && ((window.dragging && !appSlot.lifted) || window.insertIndex >= 0)
      NumberAnimation { duration: Style.duration(150); easing.type: Easing.Bezier; easing.bezierCurve: Style.easeOut }
    }

    z: lifted ? 1 : 0

    function bodyPoint(event) {
      return appSlot.mapToItem(body, event.x, event.y)
    }

    mouse.onPressed: function(event) {
      appSlot.dragged = false
      if (event.button === Qt.LeftButton) appSlot.pressPoint = appSlot.bodyPoint(event)
    }

    mouse.onPositionChanged: function(event) {
      if (!(appSlot.mouse.pressedButtons & Qt.LeftButton)) return
      var point = appSlot.bodyPoint(event)
      var delta = window.vertical ? point.y - appSlot.pressPoint.y : point.x - appSlot.pressPoint.x
      if (Math.hypot(point.x - appSlot.pressPoint.x, point.y - appSlot.pressPoint.y) > Style.space(4)) appSlot.dragged = true
      if (!window.dragging) {
        if (Math.abs(delta) < Style.space(6)) return
        window.closeMenu()
        window.dropIndex = appSlot.index
        window.dragIndex = appSlot.index
      }
      if (!appSlot.lifted) return
      window.dragOffset = delta
      window.dropIndex = DockModel.dropIndex(appSlot.index, delta, window.slotSize, appRepeater.count)
    }

    mouse.onReleased: function(event) {
      if (!appSlot.lifted) return
      window.dropPoint = appSlot.mapToItem(body, event.x, event.y)
      var from = window.dragIndex
      var to = window.dropIndex
      appSlot.dragged = true
      window.endDrag()
      window.dock.moveItem(from, to)
    }

    mouse.onCanceled: if (appSlot.lifted) window.endDrag()

    mouse.onClicked: function(event) {
      if (appSlot.dragged) {
        appSlot.dragged = false
        return
      }
      if (event.button === Qt.RightButton) {
        window.openMenu(appSlot.modelData, appSlot)
        return
      }
      window.closeMenu()
      if (event.button === Qt.MiddleButton) window.dock.launch(appSlot.modelData)
      else window.dock.activate(appSlot.modelData)
    }

    Item {
      anchors.fill: parent
      transform: Translate {
        x: window.vertical ? 0 : appSlot.displayedShift
        y: window.vertical ? appSlot.displayedShift : 0
      }

      Image {
        x: Math.round((parent.width - window.iconSize) / 2)
        y: window.iconInset - 1
        width: window.iconSize
        height: window.iconSize
        sourceSize: Qt.size(window.iconSize * 2, window.iconSize * 2)
        source: window.dock && appSlot.modelData ? window.dock.iconFor(appSlot.modelData) : ""
        fillMode: Image.PreserveAspectFit
        asynchronous: true
        smooth: true
        mipmap: true
        opacity: appSlot.launching ? appSlot.pulse : (appSlot.windowCount > 0 ? 1 : window.idleOpacity)
        scale: appSlot.lifted || appSlot.hovered ? 1.16 : 1
        transformOrigin: window.popOrigin

        Behavior on scale { NumberAnimation { duration: Style.duration(120); easing.type: Easing.OutCubic } }
      }

      // macOS-style active app indicator dot beneath running apps.
      Rectangle {
        visible: appSlot.windowCount > 0 || appSlot.launching
        width: appSlot.focusedApp ? 6 : 4
        height: 4
        radius: 2
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottom: parent.bottom
        anchors.bottomMargin: 2
        color: Commons.Color.bar.text
        opacity: appSlot.focusedApp ? 0.95 : 0.55

        Behavior on width { NumberAnimation { duration: Style.duration(120); easing.type: Easing.OutCubic } }
        Behavior on opacity { NumberAnimation { duration: Style.duration(120); easing.type: Easing.OutCubic } }
      }
    }

    SequentialAnimation {
      running: appSlot.launching && !Style.reduceMotion
      loops: Animation.Infinite
      onRunningChanged: if (!running) appSlot.pulse = 1

      NumberAnimation { target: appSlot; property: "pulse"; from: 1; to: window.idleOpacity; duration: Style.duration(500); easing.type: Easing.InOutSine }
      NumberAnimation { target: appSlot; property: "pulse"; to: 1; duration: Style.duration(500); easing.type: Easing.InOutSine }
    }
  }

  BorderSurface {
    id: menu

    readonly property var item: window.menuItem
    readonly property bool hasEntry: !!item && !!window.dock && !!window.dock.entryFor(item)
    readonly property bool running: !!item && item.windows.length > 0

    visible: window.contentShown && (!!item || window.settingsMenu)
    width: Math.max(Style.space(140), menuColumn.implicitWidth) + padding * 2
    height: menuColumn.implicitHeight + padding * 2
    x: window.popupX(window.menuSlot, menu)
    y: window.popupY(window.menuSlot, menu)
    radius: Style.cornerRadius
    color: Commons.Color.popups.background
    borderSpec: Border.surfaceSpec("popups", "border", Commons.Color.popups.border, Style.normalBorderWidth)
    padding: Style.spacing.sm

    Column {
      id: menuColumn
      x: menu.contentLeftInset
      y: menu.contentTopInset
      width: menu.width - menu.contentLeftInset - menu.contentRightInset

      Repeater {
        model: [["top", Commons.I18n.t("dock.positionTop")], ["bottom", Commons.I18n.t("dock.positionBottom")], ["left", Commons.I18n.t("dock.positionLeft")], ["right", Commons.I18n.t("dock.positionRight")]]

        delegate: PopupMenuRow {
          required property var modelData

          visible: window.settingsMenu
          text: modelData[1]
          checked: window.position === modelData[0]
          onTriggered: {
            Util.execDetached("omarchy-dock position " + modelData[0])
            window.closeMenu()
          }
        }
      }

      PopupMenuRow {
        visible: window.settingsMenu
        text: Commons.I18n.t("dock.autoHide")
        checked: window.autohide
        onTriggered: {
          Util.execDetached("omarchy-dock autohide " + (window.autohide ? "off" : "on"))
          window.closeMenu()
        }
      }

      PopupMenuRow {
        visible: menu.hasEntry && menu.running
        text: Commons.I18n.t("dock.newWindow")
        onTriggered: {
          window.dock.launch(menu.item)
          window.closeMenu()
        }
      }

      PopupMenuRow {
        visible: menu.hasEntry
        text: menu.item && menu.item.pinned ? Commons.I18n.t("dock.removeFromDock") : Commons.I18n.t("dock.keepInDock")
        onTriggered: {
          window.dock.setPinned(menu.item.key, !menu.item.pinned)
          window.closeMenu()
        }
      }

      PopupMenuRow {
        visible: menu.running
        text: Commons.I18n.t("dock.quit")
        onTriggered: {
          window.dock.quit(menu.item)
          window.closeMenu()
        }
      }
    }
  }
}

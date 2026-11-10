import Quickshell
import Quickshell.Wayland
import Quickshell.Hyprland
import QtQuick
import QtQuick.Effects
import qs.Commons

// Workspace thumbnail in the Spaces bar: wallpaper plus live miniatures of
// its windows. Click to switch; drag horizontally to reorder Spaces; drop a
// dragged window here to move it.
Item {
  id: thumb

  required property var workspace
  required property var monitorView
  required property int spaceIndex

  readonly property bool active: monitorView.isSpaceActive(spaceIndex, workspace)
  readonly property bool dropHover: monitorView.dropTarget === workspace.id
  readonly property real step: width + monitorView.spacesSpacing
  readonly property real k: width / Math.max(1, monitorView.width)
  readonly property int radius: Math.min(monitorView.radius, 10)

  property bool pointerDragging: false
  property bool settling: false
  property real dragX: 0
  property real dragY: 0
  property var frozenThumbWindows: null

  readonly property real shiftX: {
    var from = monitorView.spaceDragIndex
    var to = monitorView.spaceDropIndex
    if (from < 0 || to < 0 || from === spaceIndex) return 0
    if (from < to && spaceIndex > from && spaceIndex <= to) return -step
    if (from > to && spaceIndex >= to && spaceIndex < from) return step
    return 0
  }

  readonly property var liveWindows: Hyprland.toplevels.values.filter(function(t) {
    return t.workspace && t.workspace.id === thumb.workspace.id && t.wayland
      && t.lastIpcObject && t.lastIpcObject.at
  })

  readonly property var windows: thumb.monitorView.spaceReordering && thumb.frozenThumbWindows !== null
    ? thumb.frozenThumbWindows
    : thumb.liveWindows

  z: pointerDragging || settling ? 100 : (shiftX !== 0 ? 2 : 1)

  Timer {
    id: settleTimer
    interval: 150
    onTriggered: thumb.settling = false
  }

  Connections {
    target: thumb.monitorView
    function onSpaceDragFreeze() {
      thumb.frozenThumbWindows = thumb.liveWindows
    }
    function onSpaceDragReset() {
      settleTimer.stop()
      thumb.pointerDragging = false
      thumb.settling = false
      thumb.frozenThumbWindows = null
      thumb.dragX = 0
      thumb.dragY = 0
    }
  }

  Item {
    id: card
    width: thumb.width
    height: thumb.height
    x: (thumb.pointerDragging || thumb.settling) ? thumb.dragX : thumb.shiftX
    y: (thumb.pointerDragging || thumb.settling) ? thumb.dragY : 0
    scale: thumb.pointerDragging ? 1.08 : (mouse.containsMouse || thumb.dropHover ? 1.06 : 1)

    Behavior on x {
      enabled: !thumb.pointerDragging && !thumb.monitorView.suppressSpaceAnim
      NumberAnimation { duration: 120; easing.type: Easing.OutCubic }
    }
    Behavior on y {
      enabled: !thumb.pointerDragging && !thumb.monitorView.suppressSpaceAnim
      NumberAnimation { duration: 120; easing.type: Easing.OutCubic }
    }
    Behavior on scale {
      NumberAnimation { duration: 120; easing.type: Easing.OutCubic }
    }

    RectangularShadow {
      anchors.fill: parent
      radius: thumb.radius
      blur: thumb.pointerDragging ? 26 : 18
      offset.y: thumb.pointerDragging ? 8 : 4
      color: Qt.rgba(0, 0, 0, thumb.pointerDragging ? 0.6 : 0.45)
    }

    Item {
      id: content
      anchors.fill: parent
      visible: false
      layer.enabled: true

      Rectangle {
        anchors.fill: parent
        color: thumb.monitorView.background
      }

      Image {
        anchors.fill: parent
        source: thumb.monitorView.controller.backgroundPath ? "file://" + thumb.monitorView.controller.backgroundPath : ""
        sourceSize.width: 320
        fillMode: Image.PreserveAspectCrop
        asynchronous: true
      }

      Repeater {
        model: ScriptModel {
          objectProp: "address"
          values: thumb.windows
        }

        ScreencopyView {
          required property var modelData
          readonly property var o: modelData.lastIpcObject

          x: (o.at[0] - (thumb.monitorView.monitor ? thumb.monitorView.monitor.x : 0)) * thumb.k
          y: (o.at[1] - (thumb.monitorView.monitor ? thumb.monitorView.monitor.y : 0)) * thumb.k
          width: o.size[0] * thumb.k
          height: o.size[1] * thumb.k
          captureSource: thumb.monitorView.controller.mapped ? modelData.wayland : null
          live: true
        }
      }
    }

    Rectangle {
      id: mask
      anchors.fill: parent
      radius: thumb.radius
      visible: false
      layer.enabled: true
    }

    MultiEffect {
      anchors.fill: parent
      source: content
      maskEnabled: true
      maskSource: mask
      maskThresholdMin: 0.5
      maskSpreadAtMin: 1.0
    }

    Rectangle {
      anchors.fill: parent
      anchors.margins: -3
      radius: thumb.radius > 0 ? thumb.radius + 3 : 0
      color: "transparent"
      border.width: thumb.active || thumb.dropHover || thumb.pointerDragging ? 3 : 1
      border.color: thumb.active || thumb.dropHover || thumb.pointerDragging
        ? thumb.monitorView.accent
        : Qt.rgba(1, 1, 1, 0.22)
    }

    Text {
      anchors.horizontalCenter: parent.horizontalCenter
      anchors.top: parent.bottom
      anchors.topMargin: 8
      text: thumb.monitorView.previewSpaceLabel(thumb.spaceIndex, thumb.workspace.name)
      textFormat: Text.PlainText
      color: "white"
      opacity: thumb.active || thumb.pointerDragging ? 1 : 0.75
      font.family: thumb.monitorView.fontFamily
      font.pixelSize: Style.font.body
      font.weight: thumb.active || thumb.pointerDragging ? Font.DemiBold : Font.Normal
    }
  }

  MouseArea {
    id: mouse

    property point startPos
    property bool moved: false

    anchors.fill: parent
    hoverEnabled: true
    enabled: thumb.monitorView.p > 0.5 && !thumb.monitorView.spaceReordering
    acceptedButtons: Qt.LeftButton
    cursorShape: thumb.pointerDragging ? Qt.ClosedHandCursor : Qt.PointingHandCursor

    onPressed: function(m) {
      startPos = mapToItem(thumb.monitorView.contentItem, m.x, m.y)
      moved = false
    }

    onPositionChanged: function(m) {
      if (!pressed || !(m.buttons & Qt.LeftButton)) return
      var g = mapToItem(thumb.monitorView.contentItem, m.x, m.y)
      var dx = g.x - startPos.x
      var dy = g.y - startPos.y
      if (!moved && Math.sqrt(dx * dx + dy * dy) > 8) {
        moved = true
        settleTimer.stop()
        thumb.settling = false
        thumb.pointerDragging = true
        thumb.monitorView.beginSpaceDrag(thumb.spaceIndex)
      }
      if (thumb.pointerDragging) {
        thumb.dragX = dx
        thumb.dragY = Math.max(-18, Math.min(24, dy * 0.35))
        thumb.monitorView.updateSpaceDrag(thumb.spaceIndex, dx)
      }
    }

    onReleased: function(m) {
      if (moved) {
        var from = thumb.monitorView.spaceDragIndex
        var to = thumb.monitorView.spaceDropIndex
        thumb.pointerDragging = false
        thumb.settling = true
        if (from >= 0 && to >= 0 && from !== to) {
          thumb.dragX = (to - from) * thumb.step
          thumb.dragY = 0
          thumb.monitorView.commitSpaceDrag()
        } else {
          thumb.monitorView.cancelSpaceDrag()
          thumb.dragX = 0
          thumb.dragY = 0
          settleTimer.restart()
        }
        return
      }
      thumb.monitorView.switchTo(thumb.workspace.id)
    }

    onCanceled: {
      if (moved) {
        thumb.pointerDragging = false
        thumb.settling = true
        thumb.monitorView.cancelSpaceDrag()
        thumb.dragX = 0
        thumb.dragY = 0
        settleTimer.restart()
      }
    }
  }
}

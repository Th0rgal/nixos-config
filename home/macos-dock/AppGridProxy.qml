import QtQuick

Item {
  id: proxy

  property var shell: null
  property var manifest: null
  property var service: null

  readonly property bool opened: !!service && service.appsOpened === true

  function open() {
    if (service && typeof service.openApps === "function") service.openApps()
  }

  function close() {
    if (service && typeof service.closeApps === "function") service.closeApps()
  }

  function toggle() {
    if (service && typeof service.toggleApps === "function") service.toggleApps()
  }
}

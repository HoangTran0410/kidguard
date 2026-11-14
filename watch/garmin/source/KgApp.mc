import Toybox.Application;
import Toybox.Lang;
import Toybox.WatchUi;

class KgApp extends Application.AppBase {
    function initialize() {
        AppBase.initialize();
    }

    // no kid yet -> straight to the connect screen; else the kids list
    function getInitialView() {
        if (Store.kids().size() == 0) {
            var v = new ConnectView(false);
            return [v, new ConnectDelegate(v)];
        }
        return [KidsMenu.build(), new KidsMenuDelegate()];
    }

    // the relay URL setting changed in Garmin Connect: nothing cached, every call reads it again
    function onSettingsChanged() as Void {
        WatchUi.requestUpdate();
    }
}

// (the root kids menu is rebuilt, not edited, when a kid is added or removed: switchToView swaps it)
module Nav {
    function showKids() {
        WatchUi.switchToView(KidsMenu.build(), new KidsMenuDelegate(), WatchUi.SLIDE_IMMEDIATE);
    }
}

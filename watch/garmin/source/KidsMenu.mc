import Toybox.Lang;
import Toybox.WatchUi;

// Root list: one row per connected kid + "+ Connect a kid". Item id = the kid's index, or :new.
module KidsMenu {
    function build() {
        var m = new WatchUi.Menu2({ :title => Util.t(Rez.Strings.Kids) });
        var a = Store.kids();
        for (var i = 0; i < a.size(); i++) {
            m.addItem(new WatchUi.MenuItem(a[i]["name"], null, i, {}));
        }
        m.addItem(new WatchUi.MenuItem(Util.t(Rez.Strings.ConnectNew), null, :new, {}));
        return m;
    }
}

class KidsMenuDelegate extends WatchUi.Menu2InputDelegate {
    function initialize() {
        Menu2InputDelegate.initialize();
    }

    function onSelect(item) {
        var id = item.getId();
        if (id == :new) {
            var v = new ConnectView(true);
            WatchUi.pushView(v, new ConnectDelegate(v), WatchUi.SLIDE_LEFT);
        } else {
            var v = new KidView(id);
            WatchUi.pushView(v, new KidViewDelegate(v), WatchUi.SLIDE_LEFT);
        }
    }
}

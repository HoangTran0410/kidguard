import Toybox.Lang;
import Toybox.Timer;
import Toybox.WatchUi;

// Request answer menu (time: +15 / +30 / Deny; app: Allow / Deny). Item id = a Dictionary {ok, min}.
module RequestMenu {
    function open(view) {
        var rq = view.info["req"];
        var m = new WatchUi.Menu2({ :title => rq["kind"].equals("app") ? rq["app"] : Util.f(Rez.Strings.ReqTime, rq["min"]) });
        if (rq["kind"].equals("app")) {
            m.addItem(new WatchUi.MenuItem(Util.t(Rez.Strings.AllowApp), null, { "ok" => true, "min" => 0 }, {}));
        } else {
            m.addItem(new WatchUi.MenuItem(Util.t(Rez.Strings.Plus15), null, { "ok" => true, "min" => 15 }, {}));
            m.addItem(new WatchUi.MenuItem(Util.t(Rez.Strings.Plus30), null, { "ok" => true, "min" => 30 }, {}));
        }
        m.addItem(new WatchUi.MenuItem(Util.t(Rez.Strings.Deny), null, { "ok" => false, "min" => 0 }, {}));
        WatchUi.pushView(m, new RequestMenuDelegate(view, rq), WatchUi.SLIDE_LEFT);
    }
}

class RequestMenuDelegate extends WatchUi.Menu2InputDelegate {
    var view;
    var rq;

    function initialize(v, r) {
        Menu2InputDelegate.initialize();
        view = v;
        rq = r;
    }

    function onSelect(item) {
        var a = item.getId();
        // toast pops itself + this menu + the kid menu when it succeeds
        var t = Toast.open(2);
        var g = new Grant(view.k, rq, a["ok"], a["min"], t);
        g.start();
    }
}

// Actions: lock (asks first), ring, stop ring, locate, message (presets).
module ActionsMenu {
    function open(view) {
        var m = new WatchUi.Menu2({ :title => Util.t(Rez.Strings.MenuActions) });
        m.addItem(new WatchUi.MenuItem(Util.t(Rez.Strings.Lock), null, :lock, {}));
        m.addItem(new WatchUi.MenuItem(Util.t(Rez.Strings.Ring), null, :ring, {}));
        m.addItem(new WatchUi.MenuItem(Util.t(Rez.Strings.RingStop), null, :ringStop, {}));
        m.addItem(new WatchUi.MenuItem(Util.t(Rez.Strings.Locate), null, :locate, {}));
        m.addItem(new WatchUi.MenuItem(Util.t(Rez.Strings.Message), null, :message, {}));
        WatchUi.pushView(m, new ActionsDelegate(view), WatchUi.SLIDE_LEFT);
    }

    // the parent's own presets (rules.msgs) else the built-in five
    function presets(view) {
        if (view.info != null && view.info["msgs"].size() > 0) {
            return view.info["msgs"];
        }
        return [Util.t(Rez.Strings.M1), Util.t(Rez.Strings.M2), Util.t(Rez.Strings.M3), Util.t(Rez.Strings.M4), Util.t(Rez.Strings.M5)];
    }

    function send(view, c) {
        var t = Toast.open(0);
        Api.cmd(view.k, c, t.method(:onResult));
    }
}

class ActionsDelegate extends WatchUi.Menu2InputDelegate {
    var view;

    function initialize(v) {
        Menu2InputDelegate.initialize();
        view = v;
    }

    function onSelect(item) {
        var id = item.getId();
        if (id == :lock) {
            WatchUi.pushView(new WatchUi.Confirmation(Util.t(Rez.Strings.ConfirmLock)), new LockConfirm(view), WatchUi.SLIDE_IMMEDIATE);
        } else if (id == :ring) {
            ActionsMenu.send(view, { "type" => "ring" });
        } else if (id == :ringStop) {
            ActionsMenu.send(view, { "type" => "ringStop" });
        } else if (id == :locate) {
            ActionsMenu.send(view, { "type" => "locate" });
        } else if (id == :message) {
            var m = new WatchUi.Menu2({ :title => Util.t(Rez.Strings.Message) });
            var p = ActionsMenu.presets(view);
            for (var i = 0; i < p.size(); i++) {
                m.addItem(new WatchUi.MenuItem(p[i].length() > 40 ? p[i].substring(0, 38) + ".." : p[i], null, i, {}));
            }
            WatchUi.pushView(m, new MessageDelegate(view, p), WatchUi.SLIDE_LEFT);
        }
    }
}

class LockConfirm extends WatchUi.ConfirmationDelegate {
    var view;
    var timer = null;

    function initialize(v) {
        ConfirmationDelegate.initialize();
        view = v;
    }

    function onResponse(response) {
        if (response == WatchUi.CONFIRM_YES) {
            // the dialog closes itself after this returns: push the toast a moment later or it would be popped with it
            timer = new Timer.Timer();
            timer.start(method(:go), 250, false);
        }
        return true;
    }

    function go() as Void {
        ActionsMenu.send(view, { "type" => "lock" });
    }
}

class MessageDelegate extends WatchUi.Menu2InputDelegate {
    var view;
    var texts;

    function initialize(v, p) {
        Menu2InputDelegate.initialize();
        view = v;
        texts = p;
    }

    function onSelect(item) {
        ActionsMenu.send(view, { "type" => "message", "text" => texts[item.getId()] });
    }
}

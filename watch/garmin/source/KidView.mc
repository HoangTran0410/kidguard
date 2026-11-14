import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Timer;
import Toybox.WatchUi;

// One kid: time left / why, battery, last seen, the open request. START / tap / long-UP opens the kid menu.
// The /state body is parsed into a small `info` Dictionary and dropped at once (memory: see README "Response size").
class KidView extends WatchUi.View {
    var idx;
    var k;
    var info = null;
    var err = null;
    var busy = false;
    var timer = null;

    function initialize(i) {
        View.initialize();
        idx = i;
        k = Store.kid(i);
    }

    function onShow() as Void {
        refresh();
        timer = new Timer.Timer();
        timer.start(method(:refresh), 60000, true);
    }

    function onHide() as Void {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
    }

    function refresh() as Void {
        if (busy || k == null) {
            return;
        }
        busy = true;
        Api.state(k, method(:onState));
        WatchUi.requestUpdate();
    }

    function onState(code, data) as Void {
        busy = false;
        if (code == 200 && data instanceof Lang.Dictionary) {
            info = parse(data);
            err = null;
        } else {
            err = Util.failText(code, data);
        }
        data = null;
        WatchUi.requestUpdate();
    }

    // keep only what the screens need (same fields as zepp/app-side/index.js summary())
    function parse(j) {
        if (j["tileKey"] instanceof Lang.String) {
            k["tk"] = j["tileKey"];   // map tiles through the relay without headers (WhereView)
        }
        var s = j["state"];
        if (!(s instanceof Lang.Dictionary)) {
            s = {};
        }
        var why = "";
        var until = 0l;
        var now = s["now"];
        if (now instanceof Lang.Dictionary) {
            why = Util.str(now["why"], 16);
            until = Util.lng(now["until"]);
        }
        var msgs = [];
        var r = Util.newestRules(j);
        if (r != null && r["msgs"] instanceof Lang.Array) {
            var ms = r["msgs"];
            for (var i = 0; i < ms.size() && msgs.size() < 12; i++) {
                var t = Util.str(ms[i], 120);
                if (!t.equals("")) {
                    msgs.add(t);
                }
            }
        }
        var loc = null;
        var l = s["location"];
        if (l instanceof Lang.Dictionary && l["lat"] != null && l["lng"] != null) {
            loc = { "lat" => l["lat"], "lng" => l["lng"], "acc" => Util.lng(l["acc"]).toNumber(), "t" => Util.lng(l["t"]) };
        }
        var req = null;
        var rq = s["requests"];
        if (rq instanceof Lang.Array && rq.size() > 0 && rq[rq.size() - 1] instanceof Lang.Dictionary) {
            var x = rq[rq.size() - 1];
            var isApp = Util.str(x["kind"], 8).equals("app");
            req = {
                "id" => Util.str(x["id"], 64),
                "kind" => isApp ? "app" : "time",
                "min" => Util.lng(x["min"]).toNumber(),
                "pkg" => Util.str(x["pkg"], 120),
                "app" => Util.str(x["app"], 24).equals("") ? Util.str(x["pkg"], 24) : Util.str(x["app"], 24)
            };
        }
        return {
            "seen" => Util.lng(j["seen"]),
            "left" => (s["leftMs"] == null) ? -1l : Util.lng(s["leftMs"]),
            "why" => why,
            "until" => until,
            "battery" => (s["battery"] == null) ? -1 : Util.lng(s["battery"]).toNumber(),
            "charging" => s["charging"] == true,
            "msgs" => msgs,
            "loc" => loc,
            "req" => req
        };
    }

    function line(dc, y, font, color, text) {
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        dc.drawText(dc.getWidth() / 2, y, font, text, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    function onUpdate(dc as Dc) as Void {
        var h = dc.getHeight();
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        if (k == null) {
            return;
        }
        line(dc, h * 12 / 100, Graphics.FONT_SMALL, Graphics.COLOR_LT_GRAY, k["name"]);
        if (info == null) {
            line(dc, h / 2, Graphics.FONT_SMALL, err == null ? Graphics.COLOR_WHITE : Graphics.COLOR_RED, err == null ? Util.t(Rez.Strings.Loading) : err);
            return;
        }
        var why = info["why"];
        var locked = !why.equals("") && !why.equals("pass") && !why.equals("code");
        var left = info["left"];
        // the big line: time left (or "No limit")
        line(dc, h * 30 / 100, Graphics.FONT_NUMBER_MEDIUM, locked ? Graphics.COLOR_RED : Graphics.COLOR_WHITE,
            left < 0l ? Util.t(Rez.Strings.NoLimit) : Util.dur(left));
        if (!why.equals("")) {
            line(dc, h * 47 / 100, Graphics.FONT_XTINY, locked ? Graphics.COLOR_RED : Graphics.COLOR_YELLOW, Util.whyText(why));
        }
        var bat = info["battery"] < 0 ? "-" : Util.f(Rez.Strings.Battery, info["battery"]);
        if (info["charging"]) {
            bat = Util.f(Rez.Strings.Charging, bat);
        }
        line(dc, h * 58 / 100, Graphics.FONT_XTINY, Graphics.COLOR_LT_GRAY, bat);
        line(dc, h * 68 / 100, Graphics.FONT_XTINY, Graphics.COLOR_LT_GRAY, Util.f(Rez.Strings.SeenFmt, Util.ago(info["seen"])));
        var rq = info["req"];
        if (rq != null) {
            var txt = rq["kind"].equals("app") ? Util.f(Rez.Strings.ReqApp, rq["app"]) : Util.f(Rez.Strings.ReqTime, rq["min"]);
            line(dc, h * 80 / 100, Graphics.FONT_XTINY, Graphics.COLOR_ORANGE, txt);
        }
        if (err != null) {
            line(dc, h * 90 / 100, Graphics.FONT_XTINY, Graphics.COLOR_RED, err);
        }
    }
}

class KidViewDelegate extends WatchUi.BehaviorDelegate {
    var view;

    function initialize(v) {
        BehaviorDelegate.initialize();
        view = v;
    }

    function onSelect() {
        openMenu();
        return true;
    }

    function onMenu() {
        openMenu();
        return true;
    }

    function openMenu() {
        if (view.k == null) {
            return;
        }
        var m = new WatchUi.Menu2({ :title => view.k["name"] });
        if (view.info != null && view.info["req"] != null) {
            var rq = view.info["req"];
            m.addItem(new WatchUi.MenuItem(Util.t(Rez.Strings.MenuAnswer), rq["kind"].equals("app") ? Util.f(Rez.Strings.ReqApp, rq["app"]) : Util.f(Rez.Strings.ReqTime, rq["min"]), :answer, {}));
        }
        m.addItem(new WatchUi.MenuItem(Util.t(Rez.Strings.MenuActions), null, :actions, {}));
        m.addItem(new WatchUi.MenuItem(Util.t(Rez.Strings.MenuWhere), null, :where, {}));
        m.addItem(new WatchUi.MenuItem(Util.t(Rez.Strings.MenuRefresh), null, :refresh, {}));
        m.addItem(new WatchUi.MenuItem(Util.t(Rez.Strings.MenuRemove), null, :remove, {}));
        WatchUi.pushView(m, new KidMenuDelegate(view), WatchUi.SLIDE_LEFT);
    }
}

class KidMenuDelegate extends WatchUi.Menu2InputDelegate {
    var view;

    function initialize(v) {
        Menu2InputDelegate.initialize();
        view = v;
    }

    function onSelect(item) {
        var id = item.getId();
        if (id == :answer) {
            RequestMenu.open(view);
        } else if (id == :actions) {
            ActionsMenu.open(view);
        } else if (id == :where) {
            var w = new WhereView(view.k, view.info == null ? null : view.info["loc"]);
            WatchUi.pushView(w, new WhereDelegate(w), WatchUi.SLIDE_LEFT);
        } else if (id == :refresh) {
            WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
            view.refresh();
        } else if (id == :remove) {
            WatchUi.pushView(new WatchUi.Confirmation(Util.t(Rez.Strings.ConfirmRemove)), new RemoveConfirm(view.idx), WatchUi.SLIDE_IMMEDIATE);
        }
    }
}

class RemoveConfirm extends WatchUi.ConfirmationDelegate {
    var idx;
    var timer = null;

    function initialize(i) {
        ConfirmationDelegate.initialize();
        idx = i;
    }

    function onResponse(response) {
        if (response == WatchUi.CONFIRM_YES) {
            Store.removeKid(idx);
            // the dialog closes itself after this returns: change the stack a moment later
            timer = new Timer.Timer();
            timer.start(method(:finish), 250, false);
        }
        return true;
    }

    // stack is [kids list, kid view, kid menu]: pop the last two, then rebuild the list (or connect when none is left)
    function finish() as Void {
        WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        if (Store.kids().size() == 0) {
            var v = new ConnectView(false);
            WatchUi.switchToView(v, new ConnectDelegate(v), WatchUi.SLIDE_IMMEDIATE);
        } else {
            Nav.showKids();
        }
    }
}

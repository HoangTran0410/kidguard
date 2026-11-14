import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Time;
import Toybox.Timer;
import Toybox.WatchUi;

// Connect by 6-digit code (docs/protocol.md "Watch connect"):
//   POST /w/new {sh: sha256hex(secret)} -> {code, exp}; show the code; every 3 s GET /w/<code> (X-KG-Secret)
//   until {url,id,token,name} arrives once; a new code when it expires (10 min).
class ConnectView extends WatchUi.View {
    var fromMenu;          // true when pushed over the kids list (pop first, then rebuild the list)
    var code = null;       // 6 digits shown
    var secret = null;
    var expS = 0;          // expiry, epoch seconds
    var err = null;        // shown under the code
    var busy = false;      // a /w/new or /w/<code> request is in flight
    var timer = null;
    var ticks = 0;
    var done = false;

    function initialize(menu) {
        View.initialize();
        fromMenu = menu;
    }

    function onShow() as Void {
        timer = new Timer.Timer();
        timer.start(method(:tick), 1000, true);
        if (code == null) {
            newCode();
        }
    }

    function onHide() as Void {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
    }

    function remaining() {
        return expS - Time.now().value();
    }

    function newCode() {
        if (busy || done) {
            return;
        }
        busy = true;
        code = null;
        secret = Util.randomSecret();
        Api.connectNew(Util.sha256hex(secret), method(:onNew));
        WatchUi.requestUpdate();
    }

    function onNew(rc, data) as Void {
        busy = false;
        if (rc == 200 && data instanceof Lang.Dictionary && data["code"] != null) {
            code = data["code"].toString();
            expS = (Util.lng(data["exp"]) / 1000l).toNumber();
            err = null;
            ticks = 0;
        } else {
            secret = null;
            err = Util.failText(rc, data);
        }
        WatchUi.requestUpdate();
    }

    // every second: redraw the countdown; every 3rd second poll; expired or failed -> a new code
    function tick() as Void {
        ticks++;
        if (code == null) {
            if (ticks % 5 == 0) {
                newCode();
            }
        } else if (remaining() <= 0) {
            newCode();
        } else if (ticks % 3 == 0 && !busy) {
            busy = true;
            Api.connectPoll(code, secret, method(:onPoll));
        }
        WatchUi.requestUpdate();
    }

    function onPoll(rc, data) as Void {
        busy = false;
        if (done) {
            return;
        }
        if (rc == 200 && data instanceof Lang.Dictionary) {
            if (data["token"] != null) {
                finish(data);
            } else {
                err = null;   // {"waiting": true, "exp": ms}
            }
        } else if (rc == 404 || rc == 403) {
            code = null;      // expired / used / not ours: start over
            newCode();
        } else {
            err = Util.failText(rc, data);   // keep the code, try again in 3 s
        }
        WatchUi.requestUpdate();
    }

    // same checks as the Zepp app: https origin, 16-hex id, 48-hex token
    function finish(d) {
        var url = d["url"];
        var id = d["id"];
        var tok = d["token"];
        if (!(url instanceof Lang.String) || !(id instanceof Lang.String) || !(tok instanceof Lang.String)
            || url.length() < 10 || !url.substring(0, 8).equals("https://") || id.length() != 16 || tok.length() != 48) {
            err = Util.failText(500, null);
            return;
        }
        var name = Util.str(d["name"], 20);
        Store.addKid({ "url" => url, "id" => id, "token" => tok, "name" => name.equals("") ? "Kid" : name });
        Store.put("wsecret", null);
        done = true;
        if (fromMenu) {
            WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        }
        Nav.showKids();
    }

    function onUpdate(dc as Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h * 15 / 100, Graphics.FONT_SMALL, Util.t(Rez.Strings.ConnectTitle), Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        if (code != null) {
            // 123 456
            var c = code.substring(0, 3) + " " + code.substring(3, code.length());
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h * 38 / 100, Graphics.FONT_NUMBER_MEDIUM, c, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            var s = remaining();
            if (s < 0) {
                s = 0;
            }
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h * 56 / 100, Graphics.FONT_XTINY, Util.f(Rez.Strings.CodeLeft, (s / 60).toString() + ":" + (s % 60).format("%02d")), Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            dc.drawText(w / 2, h * 68 / 100, Graphics.FONT_XTINY, Util.t(Rez.Strings.CodeHint), Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        } else if (err == null) {
            dc.drawText(w / 2, h / 2, Graphics.FONT_SMALL, Util.t(Rez.Strings.Starting), Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
        if (err != null) {
            dc.setColor(Graphics.COLOR_RED, Graphics.COLOR_TRANSPARENT);
            dc.drawText(w / 2, h * 82 / 100, Graphics.FONT_XTINY, err, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
    }
}

class ConnectDelegate extends WatchUi.BehaviorDelegate {
    var view;

    function initialize(v) {
        BehaviorDelegate.initialize();
        view = v;
    }

    // START / tap: ask a fresh code now
    function onSelect() {
        view.code = null;
        view.busy = false;
        view.newCode();
        return true;
    }
}

import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Timer;
import Toybox.WatchUi;

// Full-screen status ("Sending..." -> "Sent" / error). Pushed over a menu; on success it closes itself after 1.5 s and
// pops `extra` more views (the menu that started the action). BACK closes it at any time.
class Toast extends WatchUi.View {
    var msg;
    var good = false;
    var pending = true;
    var closed = false;
    var extra;
    var timer = null;

    function initialize(m, extraPops) {
        View.initialize();
        msg = m;
        extra = extraPops;
    }

    // push a new toast and hand it back so the caller can pass toast.method(:onResult) to Api
    static function open(extraPops) {
        var t = new Toast(Util.t(Rez.Strings.Sending), extraPops);
        WatchUi.pushView(t, new ToastDelegate(t), WatchUi.SLIDE_IMMEDIATE);
        return t;
    }

    function set(m, ok) {
        msg = m;
        good = ok;
        pending = false;
        WatchUi.requestUpdate();
        if (ok && !closed) {
            timer = new Timer.Timer();
            timer.start(method(:autoClose), 1500, false);
        }
    }

    function autoClose() as Void {
        if (closed) {
            return;
        }
        closed = true;
        WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        for (var i = 0; i < extra; i++) {
            WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        }
    }

    // callback of Api.cmd / Api.putRules
    function onResult(code, data) as Void {
        if (code >= 200 && code < 300) {
            var r = (data instanceof Lang.Dictionary) ? data["result"] : null;
            if (r instanceof Lang.String && !r.equals("queued") && !r.equals("ok") && !r.equals("no such sos")) {
                set(Util.str(r, 40), false);
            } else {
                set(Util.t(Rez.Strings.Sent), true);
            }
        } else {
            set(Util.failText(code, data), false);
        }
    }

    function onUpdate(dc as Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        dc.setColor(good ? Graphics.COLOR_GREEN : (pending ? Graphics.COLOR_WHITE : Graphics.COLOR_RED), Graphics.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h / 2, Graphics.FONT_SMALL, msg, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }
}

class ToastDelegate extends WatchUi.BehaviorDelegate {
    var toast;

    function initialize(t) {
        BehaviorDelegate.initialize();
        toast = t;
    }

    function close() {
        toast.closed = true;
        WatchUi.popView(WatchUi.SLIDE_IMMEDIATE);
        return true;
    }

    function onBack() {
        return close();
    }

    function onSelect() {
        return close();
    }
}

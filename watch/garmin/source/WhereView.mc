import Toybox.Communications;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

// Where: accuracy, age and ONE OpenStreetMap tile around the kid with a dot (+ accuracy circle) on top.
// UP / swipe down = zoom in, DOWN / swipe up = zoom out (13..18); tap top third = in, bottom third = out; START = reload.
// makeImageRequest cannot send the X-KG-Token header: the tile comes from the relay with the tile-only key `k`
// (state?watch=1 `tileKey`, kept in k["tk"]); without a key yet, straight from tile.openstreetmap.org.
class WhereView extends WatchUi.View {
    var k;
    var loc;               // {lat, lng, acc, t} or null
    var z;
    var bmp = null;
    var scale = 1.0;       // drawn bitmap width / 256
    var fx = 0.5;          // kid position inside the tile (0..1)
    var fy = 0.5;
    var loading = false;
    var failed = false;
    var dirty = false;     // zoom changed while a request was running: ask again when it ends
    var reqZ = 0;

    function initialize(kid, l) {
        View.initialize();
        k = kid;
        loc = l;
        z = Store.zoom();
    }

    function onShow() as Void {
        if (loc != null && bmp == null && !loading) {
            fetch();
        }
    }

    function fetch() {
        if (loc == null) {
            return;
        }
        if (loading) {
            dirty = true;
            return;
        }
        var t = Util.tileOf(loc["lat"], loc["lng"], z);
        reqZ = z;
        fx = t[2];
        fy = t[3];
        loading = true;
        failed = false;
        dirty = false;
        var side = System.getDeviceSettings().screenWidth;
        if (side > 256) {
            side = 256;
        }
        var src = k["tk"] != null
            ? kidTile(t)
            : "https://tile.openstreetmap.org/" + z.toString() + "/" + t[0].toString() + "/" + t[1].toString() + ".png";
        Communications.makeImageRequest(
            src,
            null,
            { :maxWidth => side, :maxHeight => side },
            method(:onImage));
        WatchUi.requestUpdate();
    }

    function kidTile(t) {
        return k["url"] + "/c/" + k["id"] + "/tile?z=" + z.toString() + "&x=" + t[0].toString() + "&y=" + t[1].toString() + "&k=" + k["tk"];
    }

    function onImage(rc, data) as Void {
        loading = false;
        if (rc == 200 && data != null) {
            if (data has :get) {
                data = data.get();   // BitmapReference -> BitmapResource
            }
            bmp = data;
            scale = bmp.getWidth().toFloat() / 256.0;
            failed = false;
        } else {
            bmp = null;
            failed = true;
        }
        if (dirty) {
            bmp = null;
            fetch();
        }
        WatchUi.requestUpdate();
    }

    function zoomBy(d) {
        var n = z + d;
        if (n < 13 || n > 18 || loc == null) {
            return;
        }
        z = n;
        Store.put("zoom", z);
        bmp = null;
        fetch();
        WatchUi.requestUpdate();
    }

    function onUpdate(dc as Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var cx = w / 2;
        var cy = h / 2;
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        if (loc == null) {
            dc.drawText(cx, cy, Graphics.FONT_SMALL, Util.t(Rez.Strings.NoLoc), Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            return;
        }
        if (bmp != null && reqZ == z) {
            // slide the tile so the kid's point under the screen centre; the rest of the screen stays dark
            var side = 256.0 * scale;
            dc.drawBitmap((cx - fx * side).toNumber(), (cy - fy * side).toNumber(), bmp);
            var mpp = Util.metersPerPixel(loc["lat"], z);
            var rad = (loc["acc"] / mpp * scale).toNumber();
            if (rad > 3) {
                dc.setColor(Graphics.COLOR_BLUE, Graphics.COLOR_TRANSPARENT);
                dc.setPenWidth(2);
                dc.drawCircle(cx, cy, rad);
            }
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
            dc.fillCircle(cx, cy, 8);
            dc.setColor(Graphics.COLOR_RED, Graphics.COLOR_TRANSPARENT);
            dc.fillCircle(cx, cy, 6);
        } else {
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
            dc.drawText(cx, cy, Graphics.FONT_XTINY, failed ? Util.t(Rez.Strings.MapFail) : Util.t(Rez.Strings.MapLoading), Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
        // info band (also carries the OSM attribution the tile licence asks for)
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.fillRectangle(0, h * 78 / 100, w, h - h * 78 / 100);
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, h * 83 / 100, Graphics.FONT_XTINY, Util.f(Rez.Strings.Accuracy, loc["acc"]) + " | " + Util.ago(loc["t"]), Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, h * 92 / 100, Graphics.FONT_XTINY, "z" + z.toString() + "  (c) OpenStreetMap", Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        dc.drawText(cx, h * 8 / 100, Graphics.FONT_SMALL, "+", Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }
}

class WhereDelegate extends WatchUi.BehaviorDelegate {
    var view;

    function initialize(v) {
        BehaviorDelegate.initialize();
        view = v;
    }

    function onPreviousPage() {
        view.zoomBy(1);
        return true;
    }

    function onNextPage() {
        view.zoomBy(-1);
        return true;
    }

    function onTap(evt) {
        var h = System.getDeviceSettings().screenHeight;
        var y = evt.getCoordinates()[1];
        if (y < h / 3) {
            view.zoomBy(1);
            return true;
        }
        if (y > h * 2 / 3) {
            view.zoomBy(-1);
            return true;
        }
        return false;
    }

    function onSelect() {
        view.bmp = null;
        view.fetch();
        return true;
    }
}

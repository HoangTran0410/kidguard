import * as hmUI from "@zos/ui";
import { px } from "@zos/utils";
import { DEVICE_WIDTH as W } from "../utils/config/device";

// Round screens: same layout as the square one (only Bip Max is targeted for now).
// the top ~76 px, so content starts below it and everything fits without scrolling.
const TOP = px(80);
const R = px(108);
const CY = TOP + R;
const center = (w) => (W - w) / 2;

export const L = {
  arc: { x: center(R * 2), y: CY - R, w: R * 2, h: R * 2, line_width: px(16) },
  big: { x: 0, y: CY - px(46), w: W, h: px(72), color: 0xffffff, text_size: px(62), align_h: hmUI.align.CENTER_H },
  sub: { x: 0, y: CY + px(26), w: W, h: px(32), color: 0x9e9e9e, text_size: px(24), align_h: hmUI.align.CENTER_H },
  info: {
    x: px(30), y: CY + R + px(10), w: W - px(60), h: px(108), color: 0xdddddd, text_size: px(26),
    align_h: hmUI.align.CENTER_H, text_style: hmUI.text_style.WRAP,
  },
  btn: {
    x: center(px(300)), y: CY + R + px(122), w: px(300), h: px(64), text_size: px(28), radius: px(32),
    normal_color: 0x2e7d32, press_color: 0x1b5e20,
  },
};

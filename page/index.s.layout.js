import * as hmUI from "@zos/ui";
import { px } from "@zos/utils";
import { DEVICE_WIDTH as W } from "../utils/config/device";

// Square screens (Bip Max 432 x 514). Coordinates scale with px() from the design width.
const R = px(150);
const CY = px(200);

const center = (w) => (W - w) / 2;

export const L = {
  title: { x: 0, y: px(24), w: W, h: px(44), color: 0x9e9e9e, text_size: px(30), align_h: hmUI.align.CENTER_H },
  arc: { x: center(R * 2), y: CY - R, w: R * 2, h: R * 2, line_width: px(18) },
  big: { x: 0, y: CY - px(52), w: W, h: px(84), color: 0xffffff, text_size: px(72), align_h: hmUI.align.CENTER_H },
  sub: { x: 0, y: CY + px(34), w: W, h: px(36), color: 0x9e9e9e, text_size: px(26), align_h: hmUI.align.CENTER_H },
  info: {
    x: px(24), y: CY + R + px(14), w: W - px(48), h: px(130), color: 0xdddddd, text_size: px(26),
    align_h: hmUI.align.CENTER_H, text_style: hmUI.text_style.WRAP,
  },
  btn: {
    x: center(px(320)), y: CY + R + px(150), w: px(320), h: px(72), text_size: px(30), radius: px(36),
    normal_color: 0x2e7d32, press_color: 0x1b5e20,
  },
};

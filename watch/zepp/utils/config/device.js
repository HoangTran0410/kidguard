import { getDeviceInfo } from "@zos/device";
export const { width: DEVICE_WIDTH, height: DEVICE_HEIGHT } = getDeviceInfo();
// model name for the relay's watch list ("Amazfit Bip Max"); ASCII only (it travels in an HTTP header)
export const DEVICE_NAME = String(getDeviceInfo().deviceName || "").replace(/[^\x20-\x7e]/g, "").trim().slice(0, 30);

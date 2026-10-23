// Adapted from FreeRDP 3.32.2 LibFreeRDP.java; licensed under MPL 2.0.
package com.freerdp.freerdpcore.services;
public class RdpListener implements LibFreeRDP.UIEventListener {
 public void OnSettingsChanged(int width, int height, int bpp) {}
 public boolean OnAuthenticate(StringBuilder username, StringBuilder domain,
		                       StringBuilder password) {return false;}
 public boolean OnGatewayAuthenticate(StringBuilder username, StringBuilder domain,
		                              StringBuilder password) {return false;}
 public int OnVerifiyCertificateEx(String host, long port, String commonName, String subject, String issuer,
		                         String fingerprint, long flags) {return 0;}
 public int OnVerifyChangedCertificateEx(String host, long port, String commonName, String subject, String issuer,
		                               String fingerprint, String oldSubject, String oldIssuer,
		                               String oldFingerprint, long flags) {return 0;}
 public boolean OnExperimentalFeature(int feature) {return false;}
 public void OnGraphicsUpdate(int x, int y, int width, int height) {}
 public void OnGraphicsResize(int width, int height, int bpp) {}
 public void OnRemoteClipboardChanged(String data) {}
 public void OnRemoteClipboardImageChanged(byte[] data) {}
 public void OnPointerSet(int[] pixels, int width, int height, int hotX, int hotY) {}
 public void OnPointerSetNull() {}
 public void OnPointerSetDefault() {}
 public void OnRailWindowUpdate(long windowId, int width, int height, int[] pixels) {}
 public void OnRailWindowMove(long windowId, int x, int y, int w, int h) {}
 public void OnRailWindowHide(long windowId) {}
 public void OnRailWindowDestroy(long windowId) {}
 public void OnRailSessionEnd() {}
 public void OnRailMonitoredDesktop(long[] windowIds, long activeWindowId) {}
}
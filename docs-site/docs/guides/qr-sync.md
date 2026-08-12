# QR Sync Guide

The QR Sync feature enables instant credential transfer from the desktop app to
mobile devices on the same LAN. The QR code encodes the server URL and API key,
so mobile clients can connect without manual entry.

## How It Works

```
Desktop App                    Mobile Device
(Embedded Server)              (Fuel Dashboard Mobile)
     |                               |
     |  Display QR code              |
     |  (encodes URL + API key)      |
     |                               |
     |------- scan QR code -------->|
     |                               |
     |                               | Parse URL + API key
     |                               | Save to local settings
     |                               |
     |<----- GET /fuel --------------|
     |      (with Bearer token)      |
     |                               |
     |------ fuel state JSON ------>|
     |                               |
     |                               | Display fuel bars
```

## Desktop Setup

The embedded server must be running and accessible from your mobile device:

1. **Launch the desktop app** — the server starts automatically on port 8322.
2. **Check the LAN URL** — the app displays the LAN URL (e.g.,
   `http://192.168.1.50:8322`). This is auto-detected from your network interface.
3. **Display the QR code** — from Settings, show the QR code which encodes the
   server URL and API key.

## Mobile Setup

1. **Install the Fuel Dashboard app** on your Android device.
2. **Open the app** and tap the QR scan icon.
3. **Point the camera** at the QR code displayed on the desktop app.
4. The mobile app automatically:
   - Parses the server URL and API key
   - Saves them to local settings
   - Begins polling for fuel state

## Network Requirements

- Both devices must be on the **same LAN** (Wi-Fi or wired).
- The desktop's port **8322** must not be blocked by a firewall.
- The LAN IP must be a site-local address (e.g., `192.168.x.x`, `10.x.x.x`).

## Troubleshooting

### QR code won't scan

- Ensure adequate lighting and screen brightness.
- Clean the camera lens.
- Try increasing the QR code size in Settings.

### Connection fails after scan

- Verify both devices are on the same Wi-Fi network.
- Check that no firewall is blocking port 8322 on the desktop machine.
- Verify the LAN URL is correct — the app auto-detects it, but VPNs or Docker
  bridges can interfere.
- Test manually: `curl -H "Authorization: Bearer YOUR_KEY" http://YOUR_LAN_IP:8322/fuel`

### Auto-detected LAN IP is wrong

The `getLanUrl()` function iterates network interfaces and picks the first
site-local address. If you have multiple network interfaces (e.g., Docker, VPN,
virtual machines), it may select the wrong one. You can manually override the
server URL on the mobile device after scanning.

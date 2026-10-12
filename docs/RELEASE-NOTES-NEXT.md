# DiPlay next release notes

Changes through DiPlay 0.2.16 are documented in [0.2.16 release notes](RELEASE-NOTES-0.2.16.md). Measured checks are in [VALIDATION.md](VALIDATION.md). Device acceptance and remaining failure families are tracked in [connection reliability validation](CONNECTION_RELIABILITY.md).

## Connection setup and recovery

- Fix wired CarPlay on Android 7.1 head units, where every attempt stopped at the USB network step with `SocketException: Invalid argument at PlainSocketImpl.socketBind` ([#557](https://github.com/shihabal3amri/DiPlay/issues/557)). The AirPlay listener on the wired address `fe80::2` named no network interface. Android 8 and later add the interface themselves, and Android 7.1 does not, so Linux rejected the listener. DiPlay now binds it to the VPN tunnel that owns the address, and the log records the choice as `wired AirPlay listener scope iface=tun0 index=…`. Pending in-car acceptance.

import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class AppUpdatesController extends ChangeNotifier {
  AppUpdatesController({MethodChannel? channel})
    : channel = channel ?? const MethodChannel('org.kaijinlab.hush/updates');

  final MethodChannel channel;
  Map<Object?, Object?> data = {};
  bool _requesting = false;
  bool _disposed = false;
  int _revision = 0;
  Timer? _pollTimer;
  String? error;
  bool get busy => _requesting;
  bool get available => data['available'] == true;
  bool get automatic => data['automatic'] != false;
  bool get ready => data['ready'] == true;
  String text(String key) => data[key] as String? ?? '';
  int number(String key) => (data[key] as num?)?.toInt() ?? 0;

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  Future<void> check({bool automatic = false}) =>
      _run(automatic ? 'autoCheck' : 'check');
  Future<void> setAutomatic(bool enabled) =>
      _run('automatic', {'enabled': enabled});
  Future<void> download() => _run('download');
  Future<void> install() => _run('install');

  Future<void> _run(String method, [Map<String, Object?>? args]) async {
    if (_requesting || _disposed) return;
    _requesting = true;
    error = null;
    if (method == 'download') {
      data = {...data, 'phase': 'downloading', 'received': 0};
    }
    if (method == 'check' || method == 'autoCheck') {
      data = {...data, 'phase': 'checking'};
    }
    _notify();
    final revision = ++_revision;
    var polling = false;
    final timer = _pollTimer = method == 'download'
        ? Timer.periodic(const Duration(milliseconds: 500), (_) async {
            if (polling || _disposed) return;
            polling = true;
            try {
              final status = await channel.invokeMapMethod<Object?, Object?>(
                'status',
              );
              if (!_disposed &&
                  _requesting &&
                  revision == _revision &&
                  status != null) {
                data = status;
                _notify();
              }
            } on PlatformException {
              // The download result reports any error.
            } on MissingPluginException {
              // The download result reports any error.
            } finally {
              polling = false;
            }
          })
        : null;
    try {
      final result = await channel.invokeMapMethod<Object?, Object?>(
        method,
        args,
      );
      if (!_disposed && result != null) {
        data = result;
        error = result['error'] as String?;
      }
    } on PlatformException catch (failure) {
      error = failure.message ?? 'Could not update Hush';
    } on MissingPluginException {
      error = 'Android update service unavailable';
    } finally {
      timer?.cancel();
      _requesting = false;
      data = {...data, 'phase': 'idle'};
      _notify();
    }
  }

  @override
  void dispose() {
    _disposed = true;
    _pollTimer?.cancel();
    super.dispose();
  }
}

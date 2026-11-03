import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class FeatureSettings {
  FeatureSettings(Map<Object?, Object?> data)
    : id = data['id']! as String,
      name = data['name']! as String,
      state = data['state'] as String? ?? 'UNKNOWN',
      reason = data['reason'] as String?,
      fixedUnlock = data['fixedUnlock'] as bool?,
      lockDelay = (data['lockDelay'] as num?)?.toInt(),
      unlockDelay = (data['unlockDelay'] as num?)?.toInt(),
      lock = data['lock'] == true,
      unlock = data['unlock'] == true,
      unused = data['unused'] == true,
      keepForMaps = data['maps'] == true,
      notEnabled = data['notEnabled'] == true,
      owned = data['owned'] == true,
      leftOn = data['leftOn'] == true;

  final String id, name, state;
  final String? reason;
  final bool? fixedUnlock;
  final int? lockDelay, unlockDelay;
  final bool lock, unlock, unused, notEnabled, owned, leftOn, keepForMaps;
  bool get supportsMaps =>
      const {'LOCATION', 'WIFI', 'MOBILE_DATA'}.contains(id);
  bool get isMode => id == 'AIRPLANE_MODE' || id == 'BATTERY_SAVER';
  bool get isSensor => id == 'CAMERA' || id == 'MICROPHONE';
  bool get supportsUnused => const {
    'WIFI',
    'BLUETOOTH',
    'MOBILE_DATA',
    'LOCATION',
    'MICROPHONE',
  }.contains(id);
  String get status => switch (state) {
    'ENABLED' => isSensor ? 'Allowed' : 'On',
    'DISABLED' => isSensor ? 'Blocked' : 'Off',
    'UNAVAILABLE' => 'Unavailable',
    'ERROR' => 'Could not read state',
    _ => 'Not verified',
  };
}

class HushState {
  HushState(this.data)
    : features = (data['features'] as List? ?? [])
          .map(
            (item) => FeatureSettings(Map<Object?, Object?>.from(item as Map)),
          )
          .toList();
  final Map<Object?, Object?> data;
  final List<FeatureSettings> features;
  bool flag(String key) => data[key] == true;
  int number(String key) => (data[key] as num?)?.toInt() ?? 0;
  String text(String key) => data[key] as String? ?? '';
  List<String> get exemptApps =>
      List<String>.from(data['exemptApps'] as List? ?? []);
  String get theme => text('theme').isEmpty ? 'system' : text('theme');
  String get protectionStatus {
    if (!flag('granted')) return 'Access required';
    if (!flag('enabled')) return 'Paused';
    if (!flag('running')) return 'Service stopped';
    return 'Active · ${text('method')}';
  }
}

class HushController extends ChangeNotifier {
  HushController({MethodChannel? channel})
    : channel = channel ?? const MethodChannel('org.kaijinlab.hush/control');

  final MethodChannel channel;
  HushState? state;
  String initialTheme = 'system';
  bool busy = false;
  String? error;
  bool _disposed = false;
  bool _refreshing = false;
  int _revision = 0;

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  Future<void> loadAppearance() async {
    try {
      initialTheme =
          await channel.invokeMethod<String>('appearance') ?? 'system';
    } on PlatformException {
      initialTheme = 'system';
    } on MissingPluginException {
      initialTheme = 'system';
    }
  }

  Future<void> refresh({bool readStates = true}) async {
    if (busy || _refreshing) return;
    _refreshing = true;
    final revision = _revision;
    error = null;
    if (state == null) _notify();
    try {
      final result = await channel.invokeMapMethod<Object?, Object?>(
        'snapshot',
        {'readStates': readStates},
      );
      // A response started before a user edit must not overwrite that newer edit.
      if (result != null && revision == _revision && !busy) {
        state = HushState(result);
      }
    } on PlatformException catch (e) {
      if (revision == _revision) {
        error = e.message ?? 'Could not refresh status';
      }
    } on MissingPluginException {
      if (revision == _revision) error = 'Android connection unavailable';
    } finally {
      _refreshing = false;
      _notify();
    }
  }

  Future<bool> _run(String method, [Map<String, Object?>? args]) async {
    if (busy) return false;
    _revision++;
    busy = true;
    error = null;
    _notify();
    try {
      final result = await channel.invokeMapMethod<Object?, Object?>(
        method,
        args,
      );
      if (result != null) state = HushState(result);
      return true;
    } on PlatformException catch (e) {
      error = e.message ?? 'Action failed';
      return false;
    } on MissingPluginException {
      error = 'Android connection unavailable';
      return false;
    } finally {
      busy = false;
      _notify();
    }
  }

  Future<bool> set(String key, Object value, {String? feature}) =>
      _run('set', {'key': key, 'value': value, 'feature': ?feature});
  Future<bool> authorize() => _run('authorize');
  Future<bool> setExemptApps(List<String> ids) => _run('exempt', {'ids': ids});

  Future<T?> action<T>(String method, [Map<String, Object?>? args]) async {
    try {
      return await channel.invokeMethod<T>(method, args);
    } on PlatformException catch (e) {
      error = e.message ?? 'Action failed';
      _notify();
      return null;
    } on MissingPluginException {
      error = 'Android connection unavailable';
      _notify();
      return null;
    }
  }

  Future<void> open(String target) => action<void>('open', {'target': target});
  void clearError() {
    error = null;
    _notify();
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}

String delayLabel(int seconds) => switch (seconds) {
  0 => 'Immediately',
  < 60 => '${seconds}s',
  _ when seconds % 60 == 0 => '${seconds ~/ 60}m',
  _ => '${seconds ~/ 60}m ${seconds % 60}s',
};

import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hush/hush.dart';
import 'package:hush/main.dart';
import 'package:hush/screens.dart';

Map<String, Object?> fixture({bool granted = true, String theme = 'system'}) =>
    {
      'enabled': false,
      'granted': granted,
      'available': granted,
      'method': 'Shizuku',
      'running': true,
      'battery': true,
      'notifications': true,
      'bluetoothPermission': true,
      'usageAccess': true,
      'mapsSupported': true,
      'accessibilityGranted': false,
      'lockDelay': 10,
      'unlockDelay': 1,
      'theme': theme,
      'exemptApps': <String>[],
      'features': [
        for (final entry in {
          'WIFI': 'Wi-Fi',
          'BLUETOOTH': 'Bluetooth',
          'MOBILE_DATA': 'Mobile data',
          'LOCATION': 'Location',
          'NFC': 'NFC',
          'CAMERA': 'Camera',
          'MICROPHONE': 'Microphone',
          'AIRPLANE_MODE': 'Airplane mode',
          'BATTERY_SAVER': 'Battery saver',
        }.entries)
          {
            'id': entry.key,
            'name': entry.value,
            'state': 'ENABLED',
            'lock': !['AIRPLANE_MODE', 'BATTERY_SAVER'].contains(entry.key),
            'unlock': true,
            'unused': false,
            'notEnabled': true,
            'owned': true,
          },
      ],
    };

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('org.kaijinlab.hush/control');
  late Map<String, Object?> stored;
  late List<MethodCall> calls;
  late HushController controller;

  setUpAll(() async {
    final font = FontLoader('Roboto')
      ..addFont(
        Future.value(
          ByteData.sublistView(
            await File('test/fonts/Roboto-Regular.ttf').readAsBytes(),
          ),
        ),
      );
    await font.load();
    final icons = FontLoader('MaterialIcons')
      ..addFont(rootBundle.load('fonts/MaterialIcons-Regular.otf'));
    await icons.load();
  });

  setUp(() {
    stored = fixture();
    calls = [];
    controller = HushController(channel: channel);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          final args = call.arguments as Map? ?? {};
          if (call.method == 'set') {
            if (args['feature'] == null) {
              stored[args['key'] as String] = args['value'];
            } else {
              final features = stored['features'] as List;
              final row = features.cast<Map>().firstWhere(
                (f) => f['id'] == args['feature'],
              );
              if (args['value'] == -1) {
                row.remove(args['key']);
              } else {
                row[args['key']] = args['value'];
              }
            }
          }
          if (call.method == 'apps') {
            return [
              {'id': 'org.example.music', 'name': 'Music'},
            ];
          }
          if (call.method == 'exempt') stored['exemptApps'] = args['ids'];
          if (call.method == 'logs') return '';
          return stored;
        });
  });

  tearDown(() {
    controller.dispose();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  Future<void> launch(WidgetTester tester) async {
    await tester.pumpWidget(HushApp(controller: controller));
    await tester.pumpAndSettle();
  }

  testWidgets('protection requires privileged access', (tester) async {
    stored = fixture(granted: false);
    await launch(tester);
    expect(find.text('Access required'), findsOneWidget);
    final protection = tester.widget<SwitchListTile>(
      find.byType(SwitchListTile).first,
    );
    expect(protection.onChanged, isNull);
    await tester.tap(find.text('Connect access'));
    await tester.pumpAndSettle();
    expect(find.text('Set up Shizuku'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('feature switches persist through the native bridge', (
    tester,
  ) async {
    await launch(tester);
    await tester.tap(find.byType(Switch).at(1));
    await tester.pumpAndSettle();
    final call = calls.lastWhere((c) => c.method == 'set');
    expect(call.arguments, {'key': 'lock', 'value': false, 'feature': 'WIFI'});
    expect(controller.state!.features.first.lock, false);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('theme selection persists and OLED is pure black', (
    tester,
  ) async {
    await launch(tester);
    await tester.tap(find.byTooltip('Settings'));
    await tester.pumpAndSettle();
    expect(find.text('About'), findsNothing);
    await tester.tap(find.text('Theme'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('OLED'));
    await tester.pumpAndSettle();
    final material = tester.widget<MaterialApp>(find.byType(MaterialApp));
    expect(material.themeMode, ThemeMode.dark);
    expect(material.darkTheme!.scaffoldBackgroundColor, Colors.black);
    expect(stored['theme'], 'oled');
    await tester.tap(find.text('Theme'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Light'));
    await tester.pumpAndSettle();
    expect(
      tester.widget<MaterialApp>(find.byType(MaterialApp)).themeMode,
      ThemeMode.light,
    );
    expect(stored['theme'], 'light');
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('airplane mode uses inverse lock and unlock actions', (
    tester,
  ) async {
    await launch(tester);
    push(
      tester.element(find.byType(HomeScreen)),
      FeatureScreen(controller: controller, id: 'AIRPLANE_MODE'),
    );
    await tester.pumpAndSettle();
    expect(find.text('Turn on on lock'), findsOneWidget);
    expect(find.text('Turn off on unlock'), findsOneWidget);
    expect(find.text('Only if Hush turned it on'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('fixed backend unlock policy cannot be edited', (tester) async {
    final feature = (stored['features'] as List).cast<Map>().firstWhere(
      (f) => f['id'] == 'BLUETOOTH',
    );
    feature['fixedUnlock'] = false;
    await launch(tester);
    push(
      tester.element(find.byType(HomeScreen)),
      FeatureScreen(controller: controller, id: 'BLUETOOTH'),
    );
    await tester.pumpAndSettle();
    final tile = tester.widget<SwitchListTile>(
      find.widgetWithText(SwitchListTile, 'Turn on on unlock'),
    );
    expect(tile.value, false);
    expect(tile.onChanged, isNull);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
    'native failures keep the saved configuration and show the error',
    (tester) async {
      await launch(tester);
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            throw PlatformException(
              code: 'denied',
              message: 'Access was revoked',
            );
          });
      await tester.tap(find.byType(Switch).at(1));
      await tester.pumpAndSettle();
      expect(controller.state!.features.first.lock, true);
      expect(find.text('Access was revoked'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('app exemptions save package IDs', (tester) async {
    await launch(tester);
    push(
      tester.element(find.byType(HomeScreen)),
      ExemptAppsScreen(controller: controller),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Music'));
    await tester.pumpAndSettle();
    expect(controller.state!.exemptApps, ['org.example.music']);
    await tester.pumpWidget(const SizedBox());
  });

  for (final size in [
    const Size(320, 640),
    const Size(844, 390),
    const Size(1024, 768),
  ]) {
    for (final scale in [1.0, 2.0]) {
      testWidgets('layout fits $size at text scale $scale', (tester) async {
        tester.view.physicalSize = size;
        tester.view.devicePixelRatio = 1;
        tester.platformDispatcher.textScaleFactorTestValue = scale;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
        await launch(tester);
        expect(tester.takeException(), isNull);
        await tester.drag(find.byType(ListView), const Offset(0, -700));
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        await tester.tap(find.byTooltip('Settings'));
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox());
      });
    }
  }

  for (final theme in ['oled', 'light']) {
    testWidgets('$theme home appearance', (tester) async {
      tester.view.physicalSize = const Size(390, 844);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      stored = fixture(theme: theme);
      await launch(tester);
      await expectLater(
        find.byType(HomeScreen),
        matchesGoldenFile('goldens/hush_$theme.png'),
      );
      await tester.pumpWidget(const SizedBox());
    });
  }

  testWidgets(
    'feature lock delay saves independently and can return to default',
    (tester) async {
      await launch(tester);
      push(
        tester.element(find.byType(HomeScreen)),
        FeatureScreen(controller: controller, id: 'WIFI'),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('Lock delay'));
      await tester.pumpAndSettle();
      expect(
        tester
            .widget<TextField>(find.widgetWithText(TextField, 'Seconds'))
            .controller!
            .text,
        '10',
      );
      await tester.tap(find.widgetWithText(SwitchListTile, 'Use default'));
      await tester.pumpAndSettle();
      await tester.enterText(find.widgetWithText(TextField, 'Seconds'), '5');
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Save'));
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      expect(controller.state!.features.first.lockDelay, 5);
      expect(controller.state!.features.first.unlockDelay, isNull);
      expect(calls.lastWhere((c) => c.method == 'set').arguments, {
        'key': 'lockDelay',
        'value': 5,
        'feature': 'WIFI',
      });
      await tester.tap(find.text('Lock delay'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(SwitchListTile, 'Use default'));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Save'));
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      expect(controller.state!.features.first.lockDelay, isNull);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets(
    'camera defaults to immediate and accepts an independent unlock delay',
    (tester) async {
      await launch(tester);
      push(
        tester.element(find.byType(HomeScreen)),
        FeatureScreen(controller: controller, id: 'CAMERA'),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('Unlock delay'));
      await tester.pumpAndSettle();
      expect(
        tester
            .widget<TextField>(find.widgetWithText(TextField, 'Seconds'))
            .controller!
            .text,
        '0',
      );
      await tester.tap(find.widgetWithText(SwitchListTile, 'Use default'));
      await tester.pumpAndSettle();
      await tester.enterText(find.widgetWithText(TextField, 'Seconds'), '2');
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Save'));
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      expect(
        controller.state!.features
            .firstWhere((f) => f.id == 'CAMERA')
            .unlockDelay,
        2,
      );
      expect(calls.lastWhere((c) => c.method == 'set').arguments, {
        'key': 'unlockDelay',
        'value': 2,
        'feature': 'CAMERA',
      });
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('feature timing rows fit large text on a small phone', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 640);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await launch(tester);
    push(
      tester.element(find.byType(HomeScreen)),
      FeatureScreen(controller: controller, id: 'CAMERA'),
    );
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    await tester.ensureVisible(find.text('Lock delay'));
    await tester.tap(find.text('Lock delay'));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('all nine original controls have lock and unlock configuration', (
    tester,
  ) async {
    await launch(tester);
    final homeContext = tester.element(find.byType(HomeScreen));
    expect(controller.state!.features.length, 9);
    for (final feature in controller.state!.features) {
      push(homeContext, FeatureScreen(controller: controller, id: feature.id));
      await tester.pumpAndSettle();
      expect(find.text('When locked'), findsOneWidget);
      expect(find.text('When unlocked'), findsOneWidget);
      expect(find.text('Lock delay'), findsOneWidget);
      expect(find.text('Unlock delay'), findsOneWidget);
      expect(tester.takeException(), isNull);
      Navigator.of(tester.element(find.byType(FeatureScreen))).pop();
      await tester.pumpAndSettle();
    }
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets(
    'background status refresh does not dim controls or rebuild the app theme',
    (tester) async {
      await launch(tester);
      final material = tester.widget<MaterialApp>(find.byType(MaterialApp));
      final pending = Completer<Map<String, Object?>>();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async => pending.future);
      final refresh = controller.refresh(readStates: false);
      await tester.pump();
      expect(controller.busy, false);
      expect(
        tester
            .widget<SwitchListTile>(find.byType(SwitchListTile).first)
            .onChanged,
        isNotNull,
      );
      expect(
        identical(
          tester.widget<MaterialApp>(find.byType(MaterialApp)),
          material,
        ),
        true,
      );
      pending.complete(stored);
      await refresh;
      await tester.pumpAndSettle();
      expect(
        identical(
          tester.widget<MaterialApp>(find.byType(MaterialApp)),
          material,
        ),
        true,
      );
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets(
    'a stale background response cannot overwrite a saved user action',
    (tester) async {
      await launch(tester);
      final oldSnapshot = Map<String, Object?>.from(stored);
      final pending = Completer<Map<String, Object?>>();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            if (call.method == 'snapshot') return pending.future;
            return {...stored, 'enabled': true};
          });
      final refresh = controller.refresh(readStates: false);
      await tester.pump();
      expect(await controller.set('enabled', true), true);
      pending.complete(oldSnapshot);
      await refresh;
      await tester.pumpAndSettle();
      expect(controller.state!.flag('enabled'), true);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets(
    'global and feature timers accept 30 minutes and reject values beyond it',
    (tester) async {
      await launch(tester);
      await tester.tap(find.byTooltip('Settings'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Lock delay'));
      await tester.pumpAndSettle();
      await tester.enterText(find.widgetWithText(TextField, 'Minutes'), '30');
      await tester.enterText(find.widgetWithText(TextField, 'Seconds'), '1');
      await tester.pumpAndSettle();
      expect(
        tester
            .widget<FilledButton>(find.widgetWithText(FilledButton, 'Save'))
            .onPressed,
        isNull,
      );
      await tester.enterText(find.widgetWithText(TextField, 'Seconds'), '0');
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Save'));
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      expect(controller.state!.number('lockDelay'), 1800);
      Navigator.of(tester.element(find.byType(SettingsScreen))).pop();
      await tester.pumpAndSettle();
      push(
        tester.element(find.byType(HomeScreen)),
        FeatureScreen(controller: controller, id: 'WIFI'),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('Unlock delay'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(SwitchListTile, 'Use default'));
      await tester.pumpAndSettle();
      await tester.enterText(find.widgetWithText(TextField, 'Minutes'), '30');
      await tester.enterText(find.widgetWithText(TextField, 'Seconds'), '0');
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Save'));
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      expect(controller.state!.features.first.unlockDelay, 1800);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets(
    'Maps exception is configured separately for location and internet',
    (tester) async {
      await launch(tester);
      push(
        tester.element(find.byType(HomeScreen)),
        FeatureScreen(controller: controller, id: 'LOCATION'),
      );
      await tester.pumpAndSettle();
      final toggle = find.widgetWithText(
        SwitchListTile,
        'Keep on for Google Maps',
      );
      await tester.ensureVisible(toggle);
      await tester.tap(toggle);
      await tester.pumpAndSettle();
      expect(
        controller.state!.features
            .firstWhere((f) => f.id == 'LOCATION')
            .keepForMaps,
        true,
      );
      expect(
        controller.state!.features
            .firstWhere((f) => f.id == 'WIFI')
            .keepForMaps,
        false,
      );
      expect(
        controller.state!.features
            .firstWhere((f) => f.id == 'CAMERA')
            .supportsMaps,
        false,
      );
      await tester.pumpWidget(const SizedBox());
    },
  );

  test('delay choices cover immediate, seconds and minutes', () {
    expect(delayLabel(0), 'Immediately');
    expect(delayLabel(10), '10s');
    expect(delayLabel(60), '1m');
    expect(delayLabel(120), '2m');
    expect(delayLabel(300), '5m');
    expect(delayLabel(90), '1m 30s');
    expect(delayLabel(1800), '30m');
  });
}

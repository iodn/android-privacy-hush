import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hush/main.dart';
import 'package:hush/update_settings.dart';
import 'package:hush/updates.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('org.kaijinlab.hush/updates');
  late AppUpdatesController controller;
  late Map<Object?, Object?> state;
  late List<MethodCall> calls;
  setUp(() {
    state = {
      'installed': '1.5.0',
      'automatic': true,
      'available': true,
      'version': '1.6.0',
      'notes': 'Privacy and reliability fixes.',
      'size': 20000000,
      'received': 0,
      'ready': false,
      'installAllowed': false,
      'phase': 'idle',
      'checkedAt': 1,
    };
    calls = [];
    controller = AppUpdatesController(channel: channel)..data = {...state};
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          if (call.method == 'automatic') {
            state['automatic'] = (call.arguments as Map)['enabled'];
          }
          return {...state};
        });
  });
  tearDown(() {
    controller.dispose();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });
  Future<void> show(
    WidgetTester tester, {
    Brightness brightness = Brightness.light,
    double scale = 1,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: hushTheme(brightness),
        home: MediaQuery(
          data: MediaQueryData(textScaler: TextScaler.linear(scale)),
          child: Scaffold(
            body: SafeArea(
              child: ListView(
                children: [UpdateSettings(controller: controller)],
              ),
            ),
          ),
        ),
      ),
    );
  }

  test(
    'automatic preference and manual check use the Android update channel',
    () async {
      await controller.setAutomatic(false);
      expect(controller.automatic, false);
      await controller.check();
      expect(calls.map((call) => call.method), ['automatic', 'check']);
      expect(controller.available, true);
      expect(controller.text('version'), '1.6.0');
    },
  );
  test('automatic launch check is a separate throttled action', () async {
    await controller.check(automatic: true);
    expect(calls.single.method, 'autoCheck');
  });
  testWidgets('release notes and download action are available in Settings', (
    tester,
  ) async {
    await show(tester);
    expect(find.text('Version 1.5.0'), findsOneWidget);
    await tester.tap(find.text('What’s new'));
    await tester.pumpAndSettle();
    expect(find.text('Privacy and reliability fixes.'), findsOneWidget);
    await tester.tap(find.text('Download update'));
    await tester.pumpAndSettle();
    expect(calls.last.method, 'download');
  });
  testWidgets(
    'installation permission and installation share the verified native path',
    (tester) async {
      controller.data = {...state, 'ready': true};
      await show(tester);
      expect(find.text('Allow installation'), findsOneWidget);
      state['ready'] = true;
      state['installAllowed'] = true;
      await tester.tap(find.text('Allow installation'));
      await tester.pumpAndSettle();
      expect(calls.last.method, 'install');
      expect(find.text('Install update'), findsOneWidget);
    },
  );
  testWidgets('download shows progress and refuses duplicate requests', (
    tester,
  ) async {
    final completed = Completer<Map<Object?, Object?>>();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          if (call.method == 'download') return completed.future;
          return {...state, 'phase': 'downloading', 'received': 10000000};
        });
    await show(tester);
    await tester.tap(find.text('Download update'));
    await tester.pump(const Duration(milliseconds: 600));
    await tester.pump();
    expect(find.byType(LinearProgressIndicator), findsOneWidget);
    expect(
      tester
          .widget<LinearProgressIndicator>(find.byType(LinearProgressIndicator))
          .value,
      0.5,
    );
    await controller.download();
    expect(calls.where((call) => call.method == 'download').length, 1);
    completed.complete({...state, 'ready': true});
    await tester.pumpAndSettle();
    expect(controller.ready, true);
  });
  test('failed checks are visible and can be retried', () async {
    var failed = true;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (failed) {
            throw PlatformException(code: 'network', message: 'No connection');
          }
          return {...state};
        });
    await controller.check();
    expect(controller.error, 'No connection');
    failed = false;
    await controller.check();
    expect(controller.error, isNull);
  });
  testWidgets('disposing the updater stops download polling', (tester) async {
    final completed = Completer<Map<Object?, Object?>>();
    var polls = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'download') return completed.future;
          polls++;
          return {...state};
        });
    final disposable = AppUpdatesController(channel: channel)
      ..data = {...state};
    final pending = disposable.download();
    await tester.pump(const Duration(milliseconds: 600));
    disposable.dispose();
    final before = polls;
    await tester.pump(const Duration(seconds: 2));
    expect(polls, before);
    completed.complete({...state});
    await pending;
  });
  testWidgets('late progress cannot overwrite a newer installation action', (
    tester,
  ) async {
    final downloaded = Completer<Map<Object?, Object?>>();
    final progress = Completer<Map<Object?, Object?>>();
    final installed = Completer<Map<Object?, Object?>>();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'download') return downloaded.future;
          if (call.method == 'status') return progress.future;
          return installed.future;
        });
    final download = controller.download();
    await tester.pump(const Duration(milliseconds: 600));
    downloaded.complete({...state, 'ready': true});
    await tester.pump();
    await download;
    final install = controller.install();
    progress.complete({...state, 'ready': false, 'phase': 'downloading'});
    await tester.pump();
    expect(controller.ready, true);
    installed.complete({...state, 'ready': true});
    await tester.pump();
    await install;
  });
  for (final brightness in Brightness.values) {
    for (final size in [const Size(320, 640), const Size(844, 390)]) {
      testWidgets('update controls fit $size at large text in $brightness', (
        tester,
      ) async {
        tester.view.physicalSize = size;
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        await show(tester, brightness: brightness, scale: 2);
        await tester.drag(find.byType(ListView), const Offset(0, -1200));
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
      });
    }
  }
}

import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'hush.dart';
import 'screens.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final controller = HushController();
  await controller.loadAppearance();
  runApp(HushApp(controller: controller));
}

ThemeData hushTheme(Brightness brightness) {
  final dark = brightness == Brightness.dark;
  final scheme =
      ColorScheme.fromSeed(
        seedColor: const Color(0xFF597767),
        brightness: brightness,
      ).copyWith(
        surface: dark ? Colors.black : const Color(0xFFFAFAF8),
        primary: dark ? const Color(0xFFB5D5BD) : const Color(0xFF315D42),
        onPrimary: dark ? const Color(0xFF102016) : Colors.white,
        onSurface: dark ? const Color(0xFFF1F1ED) : const Color(0xFF1C201D),
        onSurfaceVariant: dark
            ? const Color(0xFFB2B8B2)
            : const Color(0xFF545C55),
      );
  return ThemeData(
    useMaterial3: true,
    brightness: brightness,
    colorScheme: scheme,
    scaffoldBackgroundColor: scheme.surface,
    appBarTheme: AppBarTheme(
      backgroundColor: scheme.surface,
      foregroundColor: scheme.onSurface,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      scrolledUnderElevation: 0,
      centerTitle: false,
      titleTextStyle: TextStyle(
        color: scheme.onSurface,
        fontFamily: 'Roboto',
        fontSize: 23,
        fontWeight: FontWeight.w500,
      ),
      systemOverlayStyle: dark
          ? SystemUiOverlayStyle.light
          : SystemUiOverlayStyle.dark,
    ),
    listTileTheme: const ListTileThemeData(
      contentPadding: EdgeInsets.symmetric(horizontal: 24),
      minVerticalPadding: 12,
    ),
    dividerTheme: DividerThemeData(
      color: scheme.outlineVariant,
      space: 32,
      thickness: 0.5,
    ),
    bottomSheetTheme: BottomSheetThemeData(
      backgroundColor: scheme.surface,
      surfaceTintColor: Colors.transparent,
      showDragHandle: true,
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: scheme.surface,
      surfaceTintColor: Colors.transparent,
    ),
    snackBarTheme: const SnackBarThemeData(behavior: SnackBarBehavior.floating),
  );
}

class HushApp extends StatefulWidget {
  const HushApp({super.key, required this.controller});
  final HushController controller;
  @override
  State<HushApp> createState() => _HushAppState();
}

class _HushAppState extends State<HushApp> with WidgetsBindingObserver {
  final messenger = GlobalKey<ScaffoldMessengerState>();
  Timer? timer;
  String? lastError;
  late String theme;
  late final lightTheme = hushTheme(Brightness.light);
  late final darkTheme = hushTheme(Brightness.dark);

  @override
  void initState() {
    super.initState();
    theme = widget.controller.state?.theme ?? widget.controller.initialTheme;
    WidgetsBinding.instance.addObserver(this);
    widget.controller.addListener(_onChanged);
    widget.controller.refresh();
    _startTimer();
  }

  void _startTimer() {
    timer?.cancel();
    timer = Timer.periodic(
      const Duration(seconds: 8),
      (_) => widget.controller.refresh(readStates: false),
    );
  }

  void _onChanged() {
    final nextTheme =
        widget.controller.state?.theme ?? widget.controller.initialTheme;
    if (nextTheme != theme) setState(() => theme = nextTheme);
    final error = widget.controller.error;
    if (error != null && error != lastError) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        messenger.currentState?.showSnackBar(SnackBar(content: Text(error)));
      });
    }
    lastError = error;
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      widget.controller.refresh();
      _startTimer();
    } else {
      timer?.cancel();
    }
  }

  @override
  void dispose() {
    timer?.cancel();
    widget.controller.removeListener(_onChanged);
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Hush',
    debugShowCheckedModeBanner: false,
    scaffoldMessengerKey: messenger,
    theme: lightTheme,
    darkTheme: darkTheme,
    themeMode: switch (theme) {
      'light' => ThemeMode.light,
      'oled' => ThemeMode.dark,
      _ => ThemeMode.system,
    },
    home: HomeScreen(controller: widget.controller),
  );
}

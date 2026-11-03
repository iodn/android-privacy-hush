import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'hush.dart';

class HushPage extends StatelessWidget {
  const HushPage({
    super.key,
    required this.controller,
    required this.title,
    required this.content,
    this.actions,
  });
  final HushController controller;
  final String title;
  final List<Widget> Function(BuildContext, HushState) content;
  final List<Widget>? actions;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: controller,
    builder: (context, _) => Scaffold(
      appBar: AppBar(
        title: title == 'Hush'
            ? Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  ExcludeSemantics(
                    child: Image.asset(
                      'assets/monkey.png',
                      width: 28,
                      height: 28,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Text(title),
                ],
              )
            : Text(title),
        actions: actions,
      ),
      body: SafeArea(
        top: false,
        child: Align(
          alignment: Alignment.topCenter,
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 640),
            child: controller.state == null
                ? Center(
                    child: controller.error != null
                        ? TextButton(
                            onPressed: () => controller.refresh(),
                            child: const Text('Retry'),
                          )
                        : const CircularProgressIndicator(strokeWidth: 2),
                  )
                : RefreshIndicator(
                    onRefresh: () => controller.refresh(),
                    child: ListView(
                      physics: const AlwaysScrollableScrollPhysics(),
                      padding: const EdgeInsets.only(top: 8, bottom: 32),
                      children: content(context, controller.state!),
                    ),
                  ),
          ),
        ),
      ),
    ),
  );
}

void push(BuildContext context, Widget screen) {
  Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => screen));
}

Widget section(BuildContext context, String title) => Padding(
  padding: const EdgeInsets.fromLTRB(24, 24, 24, 8),
  child: Text(
    title,
    style: Theme.of(context).textTheme.labelLarge?.copyWith(
      color: Theme.of(context).colorScheme.onSurfaceVariant,
    ),
  ),
);

Widget note(BuildContext context, String text, {bool error = false}) => Padding(
  padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
  child: Text(
    text,
    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
      color: error
          ? Theme.of(context).colorScheme.error
          : Theme.of(context).colorScheme.onSurfaceVariant,
    ),
  ),
);

Widget linkRow(
  String title,
  VoidCallback? action, {
  String? value,
  String? subtitle,
}) => ListTile(
  title: Text(title),
  subtitle: subtitle == null ? null : Text(subtitle),
  trailing: value == null
      ? const Icon(Icons.chevron_right, size: 20)
      : Text(value),
  onTap: action,
  enabled: action != null,
);

IconData featureIcon(String id) => switch (id) {
  'WIFI' => Icons.wifi_outlined,
  'BLUETOOTH' => Icons.bluetooth_outlined,
  'MOBILE_DATA' => Icons.signal_cellular_alt_outlined,
  'LOCATION' => Icons.location_on_outlined,
  'NFC' => Icons.nfc_outlined,
  'CAMERA' => Icons.camera_alt_outlined,
  'MICROPHONE' => Icons.mic_none_outlined,
  'AIRPLANE_MODE' => Icons.airplanemode_active_outlined,
  _ => Icons.battery_saver_outlined,
};

class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key, required this.controller});
  final HushController controller;

  @override
  Widget build(BuildContext context) => HushPage(
    controller: controller,
    title: 'Hush',
    actions: [
      IconButton(
        tooltip: 'Settings',
        icon: const Icon(Icons.tune_outlined),
        onPressed: () => push(context, SettingsScreen(controller: controller)),
      ),
      const SizedBox(width: 8),
    ],
    content: (context, state) => [
      SwitchListTile.adaptive(
        title: const Text(
          'Protection',
          style: TextStyle(fontSize: 24, fontWeight: FontWeight.w500),
        ),
        subtitle: Text(state.protectionStatus),
        value: state.flag('enabled'),
        onChanged: controller.busy || !state.flag('granted')
            ? null
            : (v) => controller.set('enabled', v),
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 24,
          vertical: 16,
        ),
      ),
      if (!state.flag('granted'))
        linkRow(
          'Connect access',
          () => push(context, PermissionsScreen(controller: controller)),
        ),
      if (state.flag('granted') &&
          (!state.flag('battery') || !state.flag('notifications')))
        linkRow(
          'Finish setup',
          () => push(context, PermissionsScreen(controller: controller)),
        ),
      if (state.flag('enabled') &&
          state.flag('granted') &&
          !state.flag('running'))
        linkRow('Restart protection', () => controller.refresh()),
      section(context, 'Turn off on lock'),
      for (final feature in state.features.where((f) => !f.isMode))
        _featureRow(context, feature, state),
      section(context, 'Turn on on lock'),
      for (final feature in state.features.where((f) => f.isMode))
        _featureRow(context, feature, state),
    ],
  );

  Widget _featureRow(
    BuildContext context,
    FeatureSettings feature,
    HushState state,
  ) => ListTile(
    leading: ExcludeSemantics(child: Icon(featureIcon(feature.id), size: 24)),
    title: Text(feature.name),
    subtitle: feature.reason != null
        ? Text(feature.reason!, maxLines: 2, overflow: TextOverflow.ellipsis)
        : feature.id == 'BATTERY_SAVER' &&
              state.flag('charging') &&
              feature.lock &&
              feature.state != 'ENABLED'
        ? const Text('Off · Charging')
        : feature.leftOn && feature.lock
        ? Text(
            'Not blocked at last lock',
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          )
        : state.flag('granted')
        ? Text(feature.status)
        : null,
    onTap: () =>
        push(context, FeatureScreen(controller: controller, id: feature.id)),
    trailing: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Semantics(
          label: '${feature.name} on lock',
          child: Switch.adaptive(
            value: feature.lock,
            onChanged: controller.busy || feature.reason != null
                ? null
                : (v) => controller.set('lock', v, feature: feature.id),
          ),
        ),
        const ExcludeSemantics(child: Icon(Icons.chevron_right, size: 20)),
      ],
    ),
  );
}

class FeatureScreen extends StatelessWidget {
  const FeatureScreen({super.key, required this.controller, required this.id});
  final HushController controller;
  final String id;

  @override
  Widget build(BuildContext context) => HushPage(
    controller: controller,
    title: controller.state!.features.firstWhere((f) => f.id == id).name,
    content: (context, state) {
      final feature = state.features.firstWhere((f) => f.id == id);
      final available = feature.reason == null && !controller.busy;
      Widget toggle(
        String title,
        String key,
        bool value, {
        String? subtitle,
        bool enabled = true,
      }) => SwitchListTile.adaptive(
        title: Text(title),
        subtitle: subtitle == null ? null : Text(subtitle),
        value: value,
        onChanged: available && enabled
            ? (v) => controller.set(key, v, feature: id)
            : null,
      );
      return [
        if (feature.reason != null) note(context, feature.reason!),
        if (state.flag('granted'))
          ListTile(
            title: const Text('Current state'),
            trailing: Text(feature.status),
          ),
        if (id == 'BATTERY_SAVER' && state.flag('charging'))
          note(context, 'Android keeps Battery Saver off while charging.'),
        section(context, 'When locked'),
        toggle(
          feature.isMode ? 'Turn on on lock' : 'Turn off on lock',
          'lock',
          feature.lock,
        ),
        linkRow(
          'Lock delay',
          available
              ? () => showDelayPicker(
                  context,
                  controller,
                  'lockDelay',
                  feature: feature,
                )
              : null,
          value: featureDelayLabel(feature, true, state),
          subtitle: feature.lockDelay == null ? 'Use default' : null,
        ),
        if (feature.supportsUnused)
          toggle(
            id == 'BLUETOOTH'
                ? 'Only when disconnected'
                : id == 'MICROPHONE'
                ? 'Keep on during calls'
                : id == 'LOCATION'
                ? 'Keep on for any location app'
                : 'Only when unused',
            'unused',
            feature.unused,
          ),
        if (feature.supportsMaps) ...[
          toggle(
            'Keep on for Google Maps',
            'maps',
            feature.keepForMaps,
            enabled: state.flag('mapsSupported') || feature.keepForMaps,
            subtitle: state.flag('mapsSupported')
                ? 'Navigation and map viewing'
                : 'Requires Root or Shizuku',
          ),
          if (feature.keepForMaps && state.flag('mapsUnverified'))
            note(
              context,
              'Maps activity could not be verified. Kept on for now.',
            ),
        ],
        section(context, 'When unlocked'),
        toggle(
          feature.isMode ? 'Turn off on unlock' : 'Turn on on unlock',
          'unlock',
          feature.fixedUnlock ?? feature.unlock,
          enabled: feature.fixedUnlock == null,
          subtitle: feature.fixedUnlock == null
              ? null
              : 'Fixed by ${state.text('method')}',
        ),
        linkRow(
          'Unlock delay',
          available && feature.fixedUnlock == null
              ? () => showDelayPicker(
                  context,
                  controller,
                  'unlockDelay',
                  feature: feature,
                )
              : null,
          value: feature.fixedUnlock == null
              ? featureDelayLabel(feature, false, state)
              : 'Managed',
          subtitle: feature.fixedUnlock == null && feature.unlockDelay == null
              ? 'Use default'
              : null,
        ),
        if (!feature.isMode)
          toggle('Skip if already on', 'notEnabled', feature.notEnabled),
        if (feature.isMode)
          toggle('Only if Hush turned it on', 'owned', feature.owned),
        if (feature.isSensor && state.text('method') != 'Root') ...[
          note(
            context,
            'Android may refuse sensor changes after the phone locks. Immediate timing is recommended.',
          ),
          linkRow('Lock settings', () => controller.open('security')),
        ],
        if (id == 'WIFI' || id == 'MOBILE_DATA' || id == 'AIRPLANE_MODE')
          note(context, 'Left unchanged while a hotspot is active.'),
        if (id == 'BLUETOOTH' && !state.flag('bluetoothPermission'))
          linkRow(
            'Allow connection detection',
            () => controller.open('bluetooth'),
          ),
      ];
    },
  );
}

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key, required this.controller});
  final HushController controller;

  @override
  Widget build(BuildContext context) => HushPage(
    controller: controller,
    title: 'Settings',
    content: (context, state) => [
      linkRow('Theme', () => _theme(context), value: themeLabel(state.theme)),
      linkRow(
        'Lock delay',
        () => showDelayPicker(context, controller, 'lockDelay'),
        value: delayLabel(state.number('lockDelay')),
      ),
      linkRow(
        'Unlock delay',
        () => showDelayPicker(context, controller, 'unlockDelay'),
        value: delayLabel(state.number('unlockDelay')),
      ),
      linkRow(
        'Exempt apps',
        () => push(context, ExemptAppsScreen(controller: controller)),
        value: '${state.exemptApps.length}',
      ),
      const Divider(indent: 24, endIndent: 24),
      linkRow(
        'Permissions',
        () => push(context, PermissionsScreen(controller: controller)),
      ),
      linkRow(
        'Advanced',
        () => push(context, AdvancedScreen(controller: controller)),
      ),
      linkRow('Logs', () => push(context, LogsScreen(controller: controller))),
    ],
  );

  Future<void> _theme(BuildContext context) => showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    builder: (context) => SafeArea(
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (final mode in ['system', 'light', 'oled'])
              ListTile(
                title: Text(themeLabel(mode)),
                trailing: controller.state!.theme == mode
                    ? const Icon(Icons.check, size: 20)
                    : null,
                onTap: controller.busy
                    ? null
                    : () async {
                        final saved = await controller.set('theme', mode);
                        if (saved && context.mounted) Navigator.pop(context);
                      },
              ),
            const SizedBox(height: 16),
          ],
        ),
      ),
    ),
  );
}

String featureDelayLabel(
  FeatureSettings feature,
  bool locking,
  HushState state,
) {
  final override = locking ? feature.lockDelay : feature.unlockDelay;
  return delayLabel(
    override ??
        (feature.isSensor
            ? 0
            : state.number(locking ? 'lockDelay' : 'unlockDelay')),
  );
}

Future<void> showDelayPicker(
  BuildContext context,
  HushController controller,
  String key, {
  FeatureSettings? feature,
}) async {
  final override = key == 'lockDelay'
      ? feature?.lockDelay
      : feature?.unlockDelay;
  var inherit = feature != null && override == null;
  var seconds =
      override ??
      (feature?.isSensor == true ? 0 : controller.state!.number(key));
  var saving = false;
  var minutes = '${seconds ~/ 60}';
  var remainder = '${seconds % 60}';
  await showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    builder: (context) => StatefulBuilder(
      builder: (context, setState) => SafeArea(
        child: SingleChildScrollView(
          child: Padding(
            padding: EdgeInsets.fromLTRB(
              24,
              0,
              24,
              24 + MediaQuery.viewInsetsOf(context).bottom,
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  key == 'lockDelay' ? 'Lock delay' : 'Unlock delay',
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                if (feature != null)
                  SwitchListTile.adaptive(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Use default'),
                    subtitle: Text(
                      feature.isSensor
                          ? 'Immediately'
                          : 'Settings · ${key == 'lockDelay' ? 'Lock delay' : 'Unlock delay'}',
                    ),
                    value: inherit,
                    onChanged: saving
                        ? null
                        : (value) => setState(() {
                            inherit = value;
                            if (value) {
                              seconds = feature.isSensor
                                  ? 0
                                  : controller.state!.number(key);
                              minutes = '${seconds ~/ 60}';
                              remainder = '${seconds % 60}';
                            }
                          }),
                  ),
                const SizedBox(height: 24),
                Text(
                  delayLabel(seconds),
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.headlineSmall,
                ),
                const SizedBox(height: 16),
                Row(
                  children: [
                    Expanded(
                      child: TextFormField(
                        key: ValueKey('minutes-$inherit'),
                        initialValue: minutes,
                        enabled: !saving && !inherit,
                        keyboardType: TextInputType.number,
                        textInputAction: TextInputAction.next,
                        inputFormatters: [
                          FilteringTextInputFormatter.digitsOnly,
                          LengthLimitingTextInputFormatter(2),
                        ],
                        decoration: const InputDecoration(labelText: 'Minutes'),
                        onChanged: (value) => setState(() {
                          minutes = value;
                          seconds =
                              (int.tryParse(minutes) ?? 0) * 60 +
                              (int.tryParse(remainder) ?? 0);
                        }),
                      ),
                    ),
                    const SizedBox(width: 24),
                    Expanded(
                      child: TextFormField(
                        key: ValueKey('seconds-$inherit'),
                        initialValue: remainder,
                        enabled: !saving && !inherit,
                        keyboardType: TextInputType.number,
                        textInputAction: TextInputAction.done,
                        inputFormatters: [
                          FilteringTextInputFormatter.digitsOnly,
                          LengthLimitingTextInputFormatter(2),
                        ],
                        decoration: const InputDecoration(labelText: 'Seconds'),
                        onChanged: (value) => setState(() {
                          remainder = value;
                          seconds =
                              (int.tryParse(minutes) ?? 0) * 60 +
                              (int.tryParse(remainder) ?? 0);
                        }),
                      ),
                    ),
                  ],
                ),
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  child: Text(
                    (int.tryParse(remainder) ?? 0) > 59
                        ? 'Seconds: 0–59'
                        : 'Maximum: 30 minutes',
                    style: TextStyle(
                      color:
                          seconds > 1800 || (int.tryParse(remainder) ?? 0) > 59
                          ? Theme.of(context).colorScheme.error
                          : Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                  ),
                ),
                if (key == 'lockDelay' && feature == null)
                  const Padding(
                    padding: EdgeInsets.only(bottom: 16),
                    child: Text('Camera and microphone default to immediate.'),
                  ),
                FilledButton(
                  onPressed:
                      saving ||
                          seconds > 1800 ||
                          (int.tryParse(remainder) ?? 0) > 59
                      ? null
                      : () async {
                          setState(() => saving = true);
                          final saved = await controller.set(
                            key,
                            inherit ? -1 : seconds,
                            feature: feature?.id,
                          );
                          if (!context.mounted) return;
                          if (saved) {
                            Navigator.pop(context);
                          } else {
                            setState(() => saving = false);
                          }
                        },
                  child: Text(saving ? 'Saving…' : 'Save'),
                ),
              ],
            ),
          ),
        ),
      ),
    ),
  );
}

String themeLabel(String theme) => switch (theme) {
  'oled' => 'OLED',
  'light' => 'Light',
  _ => 'System',
};

class PermissionsScreen extends StatelessWidget {
  const PermissionsScreen({super.key, required this.controller});
  final HushController controller;

  @override
  Widget build(BuildContext context) => HushPage(
    controller: controller,
    title: 'Permissions',
    content: (context, state) => [
      ListTile(
        title: const Text('Privileged access'),
        subtitle: Text(
          state.flag('granted')
              ? state.text('method')
              : state.flag('available')
              ? '${state.text('method')} · Not granted'
              : 'Root, Sui, Shizuku or Dhizuku required',
        ),
        trailing: state.flag('granted')
            ? const Icon(Icons.check, semanticLabel: 'Granted')
            : TextButton(
                onPressed: controller.busy
                    ? null
                    : () => controller.authorize(),
                child: const Text('Grant'),
              ),
      ),
      if (!state.flag('available'))
        linkRow('Set up Shizuku', () => controller.open('shizuku')),
      if (!state.flag('granted'))
        linkRow('Check again', () => controller.refresh()),
      _permission('Background battery use', state.flag('battery'), 'battery'),
      _permission(
        'Notifications',
        state.flag('notifications'),
        'notifications',
      ),
      section(context, 'Optional features'),
      _permission(
        'Bluetooth connection detection',
        state.flag('bluetoothPermission'),
        'bluetooth',
        description: 'For connected-device exceptions',
      ),
      _permission(
        'Usage access',
        state.flag('usageAccess'),
        'usage',
        description: 'For exempt apps',
      ),
      _permission(
        'Accessibility',
        state.flag('accessibilityGranted'),
        'accessibility',
        description: 'Extra lock detection',
      ),
    ],
  );

  Widget _permission(
    String title,
    bool granted,
    String target, {
    String? description,
  }) => ListTile(
    title: Text(title),
    subtitle: description == null ? null : Text(description),
    trailing: granted
        ? const Icon(Icons.check, semanticLabel: 'Granted', size: 20)
        : const Icon(Icons.chevron_right, size: 20),
    onTap: () => controller.open(target),
  );
}

class AdvancedScreen extends StatelessWidget {
  const AdvancedScreen({super.key, required this.controller});
  final HushController controller;
  @override
  Widget build(BuildContext context) => HushPage(
    controller: controller,
    title: 'Advanced',
    content: (context, state) {
      Widget toggle(String title, String key, {String? subtitle}) =>
          SwitchListTile.adaptive(
            title: Text(title),
            subtitle: subtitle == null ? null : Text(subtitle),
            value: state.flag(key),
            onChanged: controller.busy ? null : (v) => controller.set(key, v),
          );
      return [
        toggle(
          'Keep off while locked',
          'keepOff',
          subtitle: 'Reapply changed controls, up to 5 times per lock.',
        ),
        toggle(
          'Retry NFC blocking',
          'nfcRetry',
          subtitle: 'If a wallet app turns NFC back on.',
        ),
        toggle('Countdown notifications', 'countdown'),
        toggle(
          'Accessibility detection',
          'accessibility',
          subtitle: 'Additional lock detection. Experimental.',
        ),
        if (state.flag('accessibility') && !state.flag('accessibilityGranted'))
          linkRow(
            'Enable in Android settings',
            () => controller.open('accessibility'),
          ),
        toggle('Debug notifications', 'debugNotifications'),
      ];
    },
  );
}

class ExemptAppsScreen extends StatefulWidget {
  const ExemptAppsScreen({super.key, required this.controller});
  final HushController controller;
  @override
  State<ExemptAppsScreen> createState() => _ExemptAppsScreenState();
}

class _ExemptAppsScreenState extends State<ExemptAppsScreen> {
  List<Map<String, String>>? apps;
  String query = '';
  bool failed = false;
  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final result = await widget.controller.action<List<dynamic>>('apps');
    if (!mounted) return;
    setState(() {
      failed = result == null;
      apps = result?.map((e) => Map<String, String>.from(e as Map)).toList();
    });
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.controller,
    builder: (context, _) {
      final state = widget.controller.state!;
      final selected = state.exemptApps.toSet();
      final visible =
          apps
              ?.where(
                (app) => '${app['name']} ${app['id']}'.toLowerCase().contains(
                  query.toLowerCase(),
                ),
              )
              .toList()
            ?..sort((a, b) {
              final difference =
                  (selected.contains(b['id']) ? 1 : 0) -
                  (selected.contains(a['id']) ? 1 : 0);
              return difference != 0
                  ? difference
                  : a['name']!.toLowerCase().compareTo(
                      b['name']!.toLowerCase(),
                    );
            });
      return Scaffold(
        appBar: AppBar(title: const Text('Exempt apps')),
        body: SafeArea(
          top: false,
          child: Align(
            alignment: Alignment.topCenter,
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 640),
              child: Column(
                children: [
                  if (!state.flag('usageAccess'))
                    ListTile(
                      title: const Text('Usage access required'),
                      trailing: TextButton(
                        onPressed: () => widget.controller.open('usage'),
                        child: const Text('Grant'),
                      ),
                    ),
                  Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 24,
                      vertical: 8,
                    ),
                    child: TextField(
                      decoration: const InputDecoration(
                        labelText: 'Search apps',
                        prefixIcon: Icon(Icons.search),
                      ),
                      onChanged: (value) => setState(() => query = value),
                    ),
                  ),
                  Expanded(
                    child: apps == null
                        ? Center(
                            child: failed
                                ? TextButton(
                                    onPressed: _load,
                                    child: const Text('Retry'),
                                  )
                                : const CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                          )
                        : visible!.isEmpty
                        ? const Center(child: Text('No apps found'))
                        : ListView.builder(
                            itemCount: visible.length,
                            itemBuilder: (context, index) {
                              final app = visible[index];
                              return CheckboxListTile(
                                title: Text(app['name']!),
                                subtitle: Text(app['id']!),
                                value: selected.contains(app['id']),
                                onChanged: widget.controller.busy
                                    ? null
                                    : (v) {
                                        final next = {...selected};
                                        if (v == true) {
                                          next.add(app['id']!);
                                        } else {
                                          next.remove(app['id']);
                                        }
                                        widget.controller.setExemptApps(
                                          next.toList(),
                                        );
                                      },
                              );
                            },
                          ),
                  ),
                ],
              ),
            ),
          ),
        ),
      );
    },
  );
}

class LogsScreen extends StatefulWidget {
  const LogsScreen({super.key, required this.controller});
  final HushController controller;
  @override
  State<LogsScreen> createState() => _LogsScreenState();
}

class _LogsScreenState extends State<LogsScreen> {
  String? logs;
  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final text = await widget.controller.action<String>('logs');
    if (mounted) setState(() => logs = text ?? 'Could not load logs');
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: widget.controller,
    builder: (context, _) => Scaffold(
      appBar: AppBar(
        title: const Text('Logs'),
        actions: [
          IconButton(
            tooltip: 'Refresh logs',
            icon: const Icon(Icons.refresh),
            onPressed: _load,
          ),
          IconButton(
            tooltip: 'Share logs',
            icon: const Icon(Icons.ios_share_outlined),
            onPressed: logs == null || logs!.isEmpty
                ? null
                : () => widget.controller.action<void>('shareLogs'),
          ),
          IconButton(
            tooltip: 'Clear logs',
            icon: const Icon(Icons.delete_outline),
            onPressed: logs == null || logs!.isEmpty
                ? null
                : () async {
                    final clear = await showDialog<bool>(
                      context: context,
                      builder: (context) => AlertDialog(
                        title: const Text('Clear logs?'),
                        actions: [
                          TextButton(
                            onPressed: () => Navigator.pop(context, false),
                            child: const Text('Cancel'),
                          ),
                          TextButton(
                            onPressed: () => Navigator.pop(context, true),
                            child: const Text('Clear'),
                          ),
                        ],
                      ),
                    );
                    if (clear == true) {
                      await widget.controller.action<void>('clearLogs');
                      await _load();
                    }
                  },
          ),
        ],
      ),
      body: SafeArea(
        top: false,
        child: Column(
          children: [
            SwitchListTile.adaptive(
              title: const Text('Record logs'),
              value: widget.controller.state!.flag('debugLogs'),
              onChanged: widget.controller.busy
                  ? null
                  : (v) async {
                      await widget.controller.set('debugLogs', v);
                      await _load();
                    },
            ),
            Expanded(
              child: logs == null
                  ? const Center(
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : logs!.isEmpty
                  ? const Center(child: Text('No logs'))
                  : SingleChildScrollView(
                      padding: const EdgeInsets.all(24),
                      child: SelectableText(
                        logs!,
                        style: const TextStyle(
                          fontFamily: 'monospace',
                          fontSize: 12,
                        ),
                      ),
                    ),
            ),
          ],
        ),
      ),
    ),
  );
}

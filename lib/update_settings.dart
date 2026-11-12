import 'package:flutter/material.dart';
import 'updates.dart';

class UpdateSettings extends StatelessWidget {
  const UpdateSettings({super.key, required this.controller});
  final AppUpdatesController controller;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: controller,
    builder: (context, _) {
      final busy = controller.busy;
      final downloading = controller.text('phase') == 'downloading';
      final size = controller.number('size');
      final received = controller.number('received');
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ListTile(
            title: const Text('App updates'),
            subtitle: Text(
              controller.text('installed').isEmpty
                  ? 'GitHub releases'
                  : 'Version ${controller.text('installed')}',
            ),
          ),
          SwitchListTile(
            title: const Text('Automatic checks'),
            subtitle: const Text('Once a day when Hush opens'),
            value: controller.automatic,
            onChanged: busy ? null : controller.setAutomatic,
          ),
          if (controller.error != null)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 8),
              child: Text(
                controller.error!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ),
          if (controller.available) ...[
            ListTile(
              title: Text('Hush ${controller.text('version')}'),
              subtitle: const Text('Update available'),
            ),
            if (controller.text('notes').isNotEmpty)
              ExpansionTile(
                title: const Text('What’s new'),
                childrenPadding: const EdgeInsets.fromLTRB(24, 0, 24, 16),
                children: [
                  Align(
                    alignment: Alignment.centerLeft,
                    child: SelectableText(controller.text('notes')),
                  ),
                ],
              ),
          ] else if (controller.number('checkedAt') > 0 &&
              !busy &&
              controller.error == null)
            const ListTile(title: Text('Hush is up to date')),
          if (downloading)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  LinearProgressIndicator(
                    value: size > 0 ? (received / size).clamp(0, 1) : null,
                    semanticsLabel: 'Downloading update',
                  ),
                  const SizedBox(height: 8),
                  Text(
                    '${(received / 1000000).toStringAsFixed(1)} / ${(size / 1000000).toStringAsFixed(1)} MB',
                  ),
                ],
              ),
            ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Wrap(
              spacing: 8,
              children: [
                TextButton(
                  onPressed: busy ? null : () => controller.check(),
                  child: Text(
                    busy && !downloading ? 'Please wait…' : 'Check for updates',
                  ),
                ),
                if (controller.available)
                  TextButton(
                    onPressed: busy
                        ? null
                        : controller.ready
                        ? controller.install
                        : controller.download,
                    child: Text(
                      downloading
                          ? 'Downloading…'
                          : !controller.ready
                          ? 'Download update'
                          : controller.data['installAllowed'] == true
                          ? 'Install update'
                          : 'Allow installation',
                    ),
                  ),
              ],
            ),
          ),
          if (controller.ready && controller.data['installAllowed'] != true)
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: 24, vertical: 8),
              child: Text(
                'Allow Hush to install apps, then return here to install the update.',
              ),
            ),
        ],
      );
    },
  );
}

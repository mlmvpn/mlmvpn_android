package org.mlmvpn.chat.bridge;

import androidx.core.content.FileProvider;

/**
 * MLMCHAT: Telegram's FileProvider under its own class name. Inside MLMVPN the manifest already has
 * a provider named androidx.core.content.FileProvider (MLMVPN's own, another authority), and two
 * providers with one class name cannot be merged. Telegram finds it by authority, so nothing else
 * changes.
 */
public class ChatFileProvider extends FileProvider {
}

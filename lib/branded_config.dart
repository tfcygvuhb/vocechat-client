class BrandedConfig {
  static const String appName = String.fromEnvironment('APP_NAME', defaultValue: 'note笔记');
  static const String defaultServerUrl = String.fromEnvironment('DEFAULT_SERVER_URL', defaultValue: '');
  static const String deepLinkScheme = String.fromEnvironment('DEEPLINK_SCHEME', defaultValue: 'notechat');
  static const String shareInvitationPrefix = String.fromEnvironment('SHARE_INVITATION_PREFIX', defaultValue: '我用 note笔记 邀请你加入聊天：');
  static const String copyright = String.fromEnvironment('APP_COPYRIGHT', defaultValue: '© 2026 note笔记。保留所有权利。');
}

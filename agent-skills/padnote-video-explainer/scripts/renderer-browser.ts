export function renderBrowserExecutableOptions(platform: string): {headless: 'shell'} | undefined {
  return platform === 'win32' ? {headless: 'shell'} : undefined;
}

export function renderBrowserOptions(platform: string) {
  const windows = platform === 'win32';
  return {
    headless: windows ? 'shell' as const : true as const,
    args: [
      ...(windows ? [] : ['--no-sandbox', '--disable-setuid-sandbox']),
      '--disable-dev-shm-usage',
      '--disable-background-networking',
    ],
  };
}

export function storyboardBrowserArgs(platform: string): string[] {
  return platform === 'win32'
    ? ['--disable-dev-shm-usage', '--disable-background-networking']
    : ['--no-sandbox', '--disable-setuid-sandbox', '--no-zygote', '--single-process',
      '--disable-dev-shm-usage', '--disable-background-networking'];
}

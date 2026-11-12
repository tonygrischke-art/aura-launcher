// Themes: Default (dark), OLED black, Light (white) and System (follows the phone: OLED black when the
// phone is dark, Light when it is light). Stored in localStorage `hm.theme`; the page's <html> gets
// data-theme="default|oled|light" (plus data-oled for the old OLED rules) and Android gets the matching
// background so the status/navigation bars and their icons match.
export type Theme = 'default' | 'oled' | 'light' | 'system'
export type Resolved = 'default' | 'oled' | 'light'

const KEY = 'hm.theme'
const BG: Record<Resolved, string> = { default: '#0E1115', oled: '#000000', light: '#FFFFFF' }

type Native = { setBackground?: (hex: string) => void; isSystemDark?: () => boolean }
const native = () => window.HermesAndroid as unknown as Native | undefined

export function savedTheme(): Theme {
  try {
    const t = localStorage.getItem(KEY)
    if (t === 'default' || t === 'oled' || t === 'light' || t === 'system') return t
    return localStorage.getItem('hm.oled') === '1' ? 'oled' : 'default' // before themes existed
  } catch {
    return 'default'
  }
}

/** Is the phone in dark mode right now? (Android tells us; a browser uses prefers-color-scheme.) */
export function systemDark(): boolean {
  try {
    const n = native()
    if (n?.isSystemDark) return n.isSystemDark()
  } catch {
    /* fall through */
  }
  return !window.matchMedia('(prefers-color-scheme: light)').matches
}

export const resolveTheme = (t: Theme): Resolved => (t === 'system' ? (systemDark() ? 'oled' : 'light') : t)

let current: Theme = 'default'

export function applyTheme(t: Theme): void {
  current = t
  const r = resolveTheme(t)
  const el = document.documentElement
  el.dataset.theme = r
  el.toggleAttribute('data-oled', r === 'oled')
  try {
    native()?.setBackground?.(BG[r])
  } catch {
    /* older shell */
  }
}

export function setTheme(t: Theme): void {
  try {
    localStorage.setItem(KEY, t)
  } catch {
    /* ignore */
  }
  applyTheme(t)
}

// "System" follows the phone live: Android calls this when the phone switches light/dark…
declare global {
  interface Window {
    __hmSystemTheme?: () => void
  }
}
window.__hmSystemTheme = () => {
  if (current === 'system') applyTheme('system')
}
// …and a plain browser tells us through the media query.
window.matchMedia('(prefers-color-scheme: light)').addEventListener?.('change', () => window.__hmSystemTheme?.())

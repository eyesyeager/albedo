import animate from 'tailwindcss-animate'

/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        brand: {
          DEFAULT: '#6D5DF6',
          hover: '#5B4BE1',
          light: '#8B7CFF',
          soft: '#EEEAFE',
          faint: '#F6F4FF',
        },
        ink: {
          DEFAULT: '#1B1D26',
          muted: '#6B7280',
          faint: '#9CA3AF',
        },
        canvas: '#F7F8FA',
        sidebar: {
          DEFAULT: '#14161C',
          raised: '#1C1F27',
          line: '#272B35',
        },
        line: '#E9EAF0',
        ok: '#16A34A',
        warn: '#F59E0B',
        danger: '#EF4444',
      },
      fontFamily: {
        sans: ['"PingFang SC"', '-apple-system', 'BlinkMacSystemFont', '"Segoe UI"', '"Microsoft YaHei"', 'Inter', 'sans-serif'],
        mono: ['"SFMono-Regular"', 'Consolas', '"Liberation Mono"', 'Menlo', 'monospace'],
      },
      fontSize: {
        '2xs': ['11px', '16px'],
        xs: ['12px', '18px'],
        sm: ['13px', '20px'],
        base: ['14px', '22px'],
        md: ['15px', '24px'],
        lg: ['18px', '26px'],
        xl: ['20px', '30px'],
        '2xl': ['24px', '34px'],
        '3xl': ['30px', '40px'],
      },
      borderRadius: {
        card: '12px',
        field: '8px',
        pill: '999px',
      },
      boxShadow: {
        card: '0 1px 2px rgba(20, 22, 28, 0.04)',
        pop: '0 16px 48px rgba(20, 22, 28, 0.16)',
        soft: '0 4px 16px rgba(109, 93, 246, 0.12)',
      },
      keyframes: {
        breathe: { '0%, 100%': { opacity: '0.35', transform: 'translateY(0)' }, '50%': { opacity: '1', transform: 'translateY(-3px)' } },
        'fade-in': { from: { opacity: '0' }, to: { opacity: '1' } },
        'slide-up': { from: { opacity: '0', transform: 'translateY(8px)' }, to: { opacity: '1', transform: 'translateY(0)' } },
        'slide-left': { from: { transform: 'translateX(100%)' }, to: { transform: 'translateX(0)' } },
      },
      animation: {
        breathe: 'breathe 1.2s ease-in-out infinite',
        'fade-in': 'fade-in 180ms ease-out',
        'slide-up': 'slide-up 220ms ease-out',
        'slide-left': 'slide-left 240ms cubic-bezier(0.2, 0.8, 0.2, 1)',
      },
    },
  },
  plugins: [animate],
}

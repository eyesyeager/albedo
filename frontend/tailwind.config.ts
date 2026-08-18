import type { Config } from 'tailwindcss'
import animate from 'tailwindcss-animate'

/**
 * Tailwind 配置。
 *
 * 🔴 断点唯一基线（与 @UI design-system.md 一致，移动优先）：
 *   Mobile  < 768px      默认（无前缀）
 *   Tablet  768~1023px   md:
 *   Desktop ≥ 1024px     lg:
 *   Wide    ≥ 1440px     xl:
 * Tailwind 默认 sm=640 与设计规范不符，已被移除，禁止使用。
 *
 * 🔴 颜色 / 圆角 / 阴影 / 动效时长一律引用 `src/styles/tokens.css` 的语义变量，
 *    禁止在此写死色值（值由 @UI 在 tokens.css 中填充）。
 */
export default {
  darkMode: ['class', '.dark'],
  content: ['./index.html', './src/**/*.{vue,ts,tsx}'],
  theme: {
    screens: {
      md: '768px',
      lg: '1024px',
      xl: '1440px',
    },
    extend: {
      colors: {
        brand: {
          DEFAULT: 'var(--color-brand)',
          hover: 'var(--color-brand-hover)',
          pressed: 'var(--color-brand-pressed)',
        },
        success: 'var(--color-success)',
        warning: 'var(--color-warning)',
        danger: 'var(--color-danger)',
        info: 'var(--color-info)',
        text: {
          primary: 'var(--color-text-primary)',
          secondary: 'var(--color-text-secondary)',
          tertiary: 'var(--color-text-tertiary)',
          'on-brand': 'var(--color-text-on-brand)',
        },
        surface: {
          base: 'var(--color-bg-base)',
          subtle: 'var(--color-bg-subtle)',
          elevated: 'var(--color-bg-elevated)',
        },
        fill: {
          subtle: 'var(--color-fill-subtle)',
          DEFAULT: 'var(--color-fill-default)',
          strong: 'var(--color-fill-strong)',
        },
        border: {
          DEFAULT: 'var(--color-border)',
          strong: 'var(--color-border-strong)',
        },
      },
      borderRadius: {
        xs: 'var(--radius-xs)',
        sm: 'var(--radius-sm)',
        md: 'var(--radius-md)',
        lg: 'var(--radius-lg)',
        xl: 'var(--radius-xl)',
        full: 'var(--radius-full)',
      },
      boxShadow: {
        sm: 'var(--shadow-sm)',
        md: 'var(--shadow-md)',
        lg: 'var(--shadow-lg)',
      },
      spacing: {
        xs: 'var(--spacing-xs)',
        sm: 'var(--spacing-sm)',
        md: 'var(--spacing-md)',
        base: 'var(--spacing-base)',
        lg: 'var(--spacing-lg)',
        xl: 'var(--spacing-xl)',
        '2xl': 'var(--spacing-2xl)',
        '3xl': 'var(--spacing-3xl)',
      },
      transitionDuration: {
        instant: 'var(--duration-instant)',
        fast: 'var(--duration-fast)',
        normal: 'var(--duration-normal)',
        slow: 'var(--duration-slow)',
        page: 'var(--duration-page)',
      },
      transitionTimingFunction: {
        enter: 'var(--ease-enter)',
        move: 'var(--ease-move)',
        exit: 'var(--ease-exit)',
      },
      zIndex: {
        dropdown: 'var(--z-dropdown)',
        sticky: 'var(--z-sticky)',
        drawer: 'var(--z-drawer)',
        modal: 'var(--z-modal)',
        toast: 'var(--z-toast)',
      },
    },
  },
  plugins: [animate],
} satisfies Config

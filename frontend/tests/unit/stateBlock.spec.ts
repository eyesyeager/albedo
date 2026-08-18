import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import StateBlock from '@/components/common/StateBlock.vue'

/**
 * `StateBlock` 的回归守护（对应缺陷 D-007）。
 *
 * <p>背景：该组件模板使用了 `<AppButton>` 但 `<script setup>` 漏了 import，
 * 导致运行时 Vue 抛 "Failed to resolve component: AppButton" 并**静默不渲染按钮**——
 * 会话列表失败态的「重试」按钮因此完全失效，而 `vue-tsc` 不校验模板中未导入的组件，
 * 类型检查与构建均无法拦截。只有真实挂载才能发现，故必须由本测试守护。
 *
 * <p>纪律：任何状态块（空态/失败态/无权限态）都必须「可理解原因 + 可执行下一步」，
 * 按钮渲染不出来等于下一步不可执行，属功能缺陷而非样式问题。
 */
describe('StateBlock', () => {
  it('有 actionText 时必须真实渲染出可点击按钮（防组件未注册导致静默不渲染）', () => {
    const wrapper = mount(StateBlock, {
      props: { title: '加载失败', actionText: '重试' },
    })

    const button = wrapper.find('button')
    expect(button.exists()).toBe(true)
    expect(button.text()).toContain('重试')
    // 未解析的组件会退化为同名自定义元素，据此断言组件确实被解析
    expect(wrapper.html()).not.toContain('<appbutton')
  })

  it('点击操作按钮向外抛出 action 事件', async () => {
    const wrapper = mount(StateBlock, {
      props: { title: '加载失败', actionText: '重试' },
    })

    await wrapper.find('button').trigger('click')

    expect(wrapper.emitted('action')).toHaveLength(1)
  })

  it('未提供 actionText 时不渲染按钮（空态无需操作）', () => {
    const wrapper = mount(StateBlock, { props: { title: '暂无内容' } })

    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('展示标题与描述，且状态不只靠颜色表达（附带文本语义）', () => {
    const wrapper = mount(StateBlock, {
      props: { title: '站点配置异常', description: '请稍后重试或联系管理员', tone: 'danger' },
    })

    expect(wrapper.text()).toContain('站点配置异常')
    expect(wrapper.text()).toContain('请稍后重试或联系管理员')
    expect(wrapper.attributes('role')).toBe('status')
  })
})

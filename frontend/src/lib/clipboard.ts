function fallbackCopy(text: string): boolean {
  const element = document.createElement('textarea')
  element.value = text
  element.style.position = 'fixed'
  element.style.opacity = '0'
  document.body.appendChild(element)
  element.select()
  const ok = document.execCommand('copy')
  document.body.removeChild(element)
  return ok
}

export function copyText(text: string): Promise<boolean> {
  if (navigator.clipboard?.writeText) {
    return navigator.clipboard
      .writeText(text)
      .then(() => true)
      .catch(() => fallbackCopy(text))
  }
  return Promise.resolve(fallbackCopy(text))
}

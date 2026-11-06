export class ApiError extends Error {
  code: string

  constructor(code: string, message: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

export function asError(error: unknown): Error {
  return error instanceof Error ? error : new Error(String(error))
}

import { create } from 'zustand'

interface AuthState {
  authenticated: boolean
  login: () => void
  logout: () => void
  setAuthenticated: (value: boolean) => void
}

export const useAuth = create<AuthState>()((set) => ({
  authenticated: false,
  login: () => set({ authenticated: true }),
  logout: () => set({ authenticated: false }),
  setAuthenticated: (value) => set({ authenticated: value }),
}))

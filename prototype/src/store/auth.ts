import { ref } from "vue";
import { currentUser } from "@/data/mock";
import type { User } from "@/types";

const TOKEN_KEY = "authorization";
const token = ref<string>(localStorage.getItem(TOKEN_KEY) || "");
const user = ref<User | null>(token.value ? currentUser : null);

export function useAuth() {
  function saveToken(t: string) {
    token.value = t;
    localStorage.setItem(TOKEN_KEY, t);
    user.value = currentUser;
  }
  function logout() {
    token.value = "";
    user.value = null;
    localStorage.removeItem(TOKEN_KEY);
  }
  function isLoggedIn() {
    return !!token.value;
  }
  return { token, user, saveToken, logout, isLoggedIn };
}

import { create } from "zustand";
import { onLogout } from "@/lib/auth";

/** Open state of the mobile navigation drawer — shared by the Topbar hamburger and the bottom bar's "More" tab. */
interface MobileNavState {
  open: boolean;
  setOpen: (open: boolean) => void;
}

export const useMobileNavStore = create<MobileNavState>((set) => ({
  open: false,
  setOpen: (open) => set({ open }),
}));

onLogout(() => useMobileNavStore.setState({ open: false }));

import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';

export interface LayoutConfig {
  x: number;
  y: number;
  scale: number;
}

export type ControlType = 'iso' | 'shutter' | 'focus' | 'wb' | null;

interface CameraStore {
  // Camera State
  isRecording: boolean;
  bitDepth: 8 | 10;
  iso: number;
  shutter: number;
  focus: number;
  selectedResolution: { width: number; height: number; fps: number; label: string };

  // UI State
  isProMode: boolean;
  isEditMode: boolean;
  activeControl: ControlType;
  showGrid: boolean;
  isLocked: boolean;

  // Layout Persistence
  layout: Record<string, LayoutConfig>;

  // Actions
  setRecording: (value: boolean) => void;
  setBitDepth: (value: 8 | 10) => void;
  setIso: (value: number) => void;
  setShutter: (value: number) => void;
  setFocus: (value: number) => void;
  setResolution: (resolution: { width: number; height: number; fps: number; label: string }) => void;

  toggleProMode: () => void;
  toggleEditMode: () => void;
  setActiveControl: (control: ControlType) => void;
  toggleGrid: () => void;
  toggleLock: () => void;
  updateLayout: (id: string, config: Partial<LayoutConfig>) => void;
  resetLayout: () => void;
}

const DEFAULT_LAYOUT: Record<string, LayoutConfig> = {
  histogram: { x: 20, y: 60, scale: 1 },
  status_pills: { x: 160, y: 60, scale: 1 },
  tech_specs: { x: 20, y: 300, scale: 1 }, // Left side
  audio_meter: { x: 0, y: 300, scale: 1 }, // Absolute left
  shutter_button: { x: 0, y: 0, scale: 1 }, // Handled by flex layout usually, but draggable needs coords
};

export const useCameraStore = create<CameraStore>()(
  persist(
    (set) => ({
      isRecording: false,
      bitDepth: 8,
      iso: 800,
      shutter: 1 / 50,
      focus: 0.0,
      selectedResolution: { width: 3840, height: 2160, fps: 30, label: '4K 30' },

      isProMode: false,
      isEditMode: false,
      activeControl: null,
      showGrid: true,
      isLocked: false,
      layout: DEFAULT_LAYOUT,

      setRecording: (value) => set({ isRecording: value }),
      setBitDepth: (value) => set({ bitDepth: value }),
      setIso: (value) => set({ iso: value }),
      setShutter: (value) => set({ shutter: value }),
      setFocus: (value) => set({ focus: value }),
      setResolution: (resolution) => set({ selectedResolution: resolution }),

      toggleProMode: () => set((state) => ({ isProMode: !state.isProMode })),
      toggleEditMode: () => set((state) => ({ isEditMode: !state.isEditMode })),
      setActiveControl: (control) => set({ activeControl: control }),
      toggleGrid: () => set((state) => ({ showGrid: !state.showGrid })),
      toggleLock: () => set((state) => ({ isLocked: !state.isLocked })),

      updateLayout: (id, config) => set((state) => ({
        layout: {
          ...state.layout,
          [id]: { ...state.layout[id] || { x: 0, y: 0, scale: 1 }, ...config }
        }
      })),
      resetLayout: () => set({ layout: DEFAULT_LAYOUT }),
    }),
    {
      name: 'camera-storage',
      storage: createJSONStorage(() => AsyncStorage),
      partialize: (state) => ({
        layout: state.layout,
        isProMode: state.isProMode,
        selectedResolution: state.selectedResolution
      }),
    }
  )
);

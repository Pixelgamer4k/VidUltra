import { create } from 'zustand';

interface CameraStore {
  isRecording: boolean;
  bitDepth: 8 | 10;
  iso: number;
  shutter: number;
  focus: number;
  activeControl: string | null;
  selectedResolution: { width: number; height: number; fps: number; label: string };
  
  setRecording: (value: boolean) => void;
  setBitDepth: (value: 8 | 10) => void;
  setIso: (value: number) => void;
  setShutter: (value: number) => void;
  setFocus: (value: number) => void;
  setActiveControl: (value: string | null) => void;
  setResolution: (resolution: { width: number; height: number; fps: number; label: string }) => void;
}

export const useCameraStore = create<CameraStore>((set) => ({
  isRecording: false,
  bitDepth: 8,
  iso: 400,
  shutter: 50,
  focus: 0,
  activeControl: null,
  selectedResolution: { width: 3840, height: 2160, fps: 60, label: '4K' },
  
  setRecording: (value) => set({ isRecording: value }),
  setBitDepth: (value) => set({ bitDepth: value }),
  setIso: (value) => set({ iso: value }),
  setShutter: (value) => set({ shutter: value }),
  setFocus: (value) => set({ focus: value }),
  setActiveControl: (value) => set({ activeControl: value }),
  setResolution: (resolution) => set({ selectedResolution: resolution }),
}));

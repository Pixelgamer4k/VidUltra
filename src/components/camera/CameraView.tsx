import React, { useRef } from 'react';
import { StyleSheet } from 'react-native';
import { Camera, useCameraDevice, useCameraFormat } from 'react-native-vision-camera';
import { useCameraStore } from '../../stores/cameraStore';

export function CameraView() {
    const camera = useRef<Camera>(null);
    const device = useCameraDevice('back');
    const { selectedResolution } = useCameraStore();

    const format = useCameraFormat(device, [
        { videoResolution: { width: selectedResolution.width, height: selectedResolution.height } },
        { fps: selectedResolution.fps }
    ]);

    if (!device) {
        return null;
    }

    return (
        <Camera
            ref={camera}
            style={StyleSheet.absoluteFill}
            device={device}
            format={format}
            isActive={true}
            video={true}
            audio={true}
        />
    );
}

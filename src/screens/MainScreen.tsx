import React, { useRef, useState, useEffect } from 'react';
import { View, StyleSheet, Dimensions, Text } from 'react-native';
import { Camera, useCameraDevice, useCameraFormat } from 'react-native-vision-camera';
import { GestureHandlerRootView, TapGestureHandler } from 'react-native-gesture-handler';
import Animated from 'react-native-reanimated';
import { useCameraStore } from '../stores/cameraStore';
import { COLORS } from '../theme';

// Components
import { GridOverlay } from '../components/ui/GridOverlay';
import { FocusReticle } from '../components/ui/FocusReticle';
import { GlassView } from '../components/ui/GlassView';

// HUD
import { Histogram } from '../components/hud/Histogram';
import { StatusPills } from '../components/hud/StatusPills';
import { TechSpecs } from '../components/hud/TechSpecs';
import { AudioMeter } from '../components/hud/AudioMeter';

// Controls
import { TopToolbar } from '../components/controls/TopToolbar';
import { BottomDock } from '../components/controls/BottomDock';
import { DialContainer } from '../components/controls/DialContainer';
import { ShutterButton } from '../components/controls/ShutterButton';

// Layout
import { DraggableItem } from '../components/layout/DraggableItem';

const { width, height } = Dimensions.get('window');

export function MainScreen() {
    const device = useCameraDevice('back');
    const [hasPermission, setHasPermission] = useState(false);
    const {
        selectedResolution,
        showGrid,
        activeControl,
        layout,
        isProMode,
        iso,
        shutter,
        focus
    } = useCameraStore();

    const camera = useRef<Camera>(null);
    const [focusPos, setFocusPos] = useState({ x: 0, y: 0, visible: false });

    // Request camera permission
    useEffect(() => {
        (async () => {
            const status = await Camera.getCameraPermissionStatus();
            if (status === 'granted') {
                setHasPermission(true);
            } else {
                const newStatus = await Camera.requestCameraPermission();
                setHasPermission(newStatus === 'granted');
            }
        })();
    }, []);

    const format = useCameraFormat(device, [
        { videoResolution: selectedResolution },
        { fps: selectedResolution.fps }
    ]);

    // Calculate Exposure Bias (EV) for simulated manual control
    // Base: ISO 100, Shutter 1/50
    const exposureBias = React.useMemo(() => {
        if (!isProMode) return 0;

        // ISO Stops: log2(ISO / 100)
        const isoStops = Math.log2(Math.max(100, iso) / 100);

        // Shutter Stops: log2(Shutter / (1/50))
        // Note: Shutter is duration in seconds. 1/50 = 0.02s
        const shutterStops = Math.log2(Math.max(0.0001, shutter) / (1 / 50));

        const totalStops = isoStops + shutterStops;

        // Clamp to device capabilities
        if (device) {
            return Math.max(device.minExposure, Math.min(device.maxExposure, totalStops));
        }
        return 0;
    }, [isProMode, iso, shutter, device]);

    const onFocusTap = (event: any) => {
        const { x, y } = event.nativeEvent;
        setFocusPos({ x, y, visible: true });
        camera.current?.focus({ x, y });
    };

    if (!hasPermission) return (
        <View style={styles.blackScreen}>
            <Text style={{ color: 'white', fontSize: 18 }}>Requesting camera permission...</Text>
        </View>
    );

    if (!device) return (
        <View style={styles.blackScreen}>
            <Text style={{ color: 'white', fontSize: 18 }}>Loading camera...</Text>
        </View>
    );

    return (
        <GestureHandlerRootView style={styles.container}>
            {/* Camera Layer */}
            <TapGestureHandler onActivated={onFocusTap}>
                <Animated.View style={styles.cameraContainer}>
                    <Camera
                        ref={camera}
                        style={StyleSheet.absoluteFill}
                        device={device}
                        format={format}
                        isActive={true}
                        video={true}
                        audio={true}
                        enableZoomGesture
                        // Manual Controls (Simulated via EV Bias)
                        exposure={exposureBias}
                    />
                    {showGrid && <GridOverlay />}
                    <FocusReticle
                        x={focusPos.x}
                        y={focusPos.y}
                        visible={focusPos.visible}
                        onAnimationComplete={() => setFocusPos(p => ({ ...p, visible: false }))}
                    />
                </Animated.View>
            </TapGestureHandler>

            {/* UI Layer - pointerEvents="box-none" allows clicks to pass through to camera */}
            <View style={styles.uiLayer} pointerEvents="box-none">

                {/* Top Toolbar (Fixed) */}
                <View style={styles.topToolbarContainer} pointerEvents="box-none">
                    <TopToolbar />
                </View>

                {/* Draggable HUD Elements */}
                <DraggableItem id="histogram" defaultPosition={{ x: 20, y: 80 }}>
                    <Histogram />
                </DraggableItem>

                <DraggableItem id="status_pills" defaultPosition={{ x: 180, y: 80 }}>
                    <StatusPills />
                </DraggableItem>

                <DraggableItem id="tech_specs" defaultPosition={{ x: width - 140, y: height - 300 }}>
                    <TechSpecs />
                </DraggableItem>

                <DraggableItem id="audio_meter" defaultPosition={{ x: 20, y: height - 300 }}>
                    <AudioMeter />
                </DraggableItem>

                {/* Controls Layer */}
                <View style={styles.controlsLayer} pointerEvents="box-none">
                    {/* Dial slides up */}
                    <DialContainer />

                    {/* Bottom Dock Area */}
                    <View style={styles.bottomDockContainer} pointerEvents="box-none">
                        {/* Shutter Button is usually fixed or part of the dock, but spec implies it's separate */}
                        <View style={styles.shutterContainer}>
                            <ShutterButton />
                        </View>

                        <BottomDock />
                    </View>
                </View>
            </View>
        </GestureHandlerRootView>
    );
}

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: 'black',
    },
    blackScreen: {
        flex: 1,
        backgroundColor: 'black',
    },
    cameraContainer: {
        flex: 1,
    },
    uiLayer: {
        ...StyleSheet.absoluteFillObject,
        zIndex: 10,
    },
    topToolbarContainer: {
        position: 'absolute',
        top: 50, // Safe area
        width: '100%',
        zIndex: 20,
    },
    controlsLayer: {
        position: 'absolute',
        bottom: 0,
        width: '100%',
        height: 300, // Area for controls
        justifyContent: 'flex-end',
        zIndex: 20,
    },
    bottomDockContainer: {
        flexDirection: 'row',
        alignItems: 'flex-end',
        justifyContent: 'center',
        paddingBottom: 40,
        gap: 20,
    },
    shutterContainer: {
        marginBottom: 0, // Align with dock
    },
});

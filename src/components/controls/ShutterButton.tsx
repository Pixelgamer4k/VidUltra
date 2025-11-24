import React, { useEffect } from 'react';
import { View, TouchableOpacity, StyleSheet } from 'react-native';
import Animated, {
    useAnimatedStyle,
    useSharedValue,
    withSpring,
    withTiming,
    withRepeat,
    interpolateColor
} from 'react-native-reanimated';
import { COLORS } from '../../theme';
import { useCameraStore } from '../../stores/cameraStore';

export function ShutterButton() {
    const { isRecording, setRecording } = useCameraStore();
    const scale = useSharedValue(1);
    const borderRadius = useSharedValue(30);
    const recordingPulse = useSharedValue(0);

    useEffect(() => {
        if (isRecording) {
            borderRadius.value = withTiming(8);
            scale.value = withSpring(0.6);
            recordingPulse.value = withRepeat(
                withTiming(1, { duration: 1000 }),
                -1,
                true
            );
        } else {
            borderRadius.value = withTiming(30);
            scale.value = withSpring(1);
            recordingPulse.value = 0;
        }
    }, [isRecording]);

    const innerStyle = useAnimatedStyle(() => ({
        transform: [{ scale: scale.value }],
        borderRadius: borderRadius.value,
    }));

    const ringStyle = useAnimatedStyle(() => {
        const borderColor = interpolateColor(
            recordingPulse.value,
            [0, 1],
            ['rgba(255,255,255,0.2)', 'rgba(239, 68, 68, 0.5)']
        );
        return { borderColor };
    });

    return (
        <TouchableOpacity
            activeOpacity={0.8}
            onPress={() => setRecording(!isRecording)}
            style={styles.container}
        >
            <Animated.View style={[styles.outerRing, ringStyle]}>
                <Animated.View style={[styles.innerButton, innerStyle]} />
            </Animated.View>
        </TouchableOpacity>
    );
}

const styles = StyleSheet.create({
    container: {
        width: 80,
        height: 80,
        justifyContent: 'center',
        alignItems: 'center',
    },
    outerRing: {
        width: 80,
        height: 80,
        borderRadius: 40,
        borderWidth: 4,
        justifyContent: 'center',
        alignItems: 'center',
    },
    innerButton: {
        width: 60,
        height: 60,
        backgroundColor: COLORS.danger,
    },
});

import React, { useEffect } from 'react';
import { StyleSheet, View } from 'react-native';
import Animated, {
    useAnimatedStyle,
    useSharedValue,
    withSpring,
    withTiming,
    withSequence,
    runOnJS
} from 'react-native-reanimated';
import { COLORS } from '../../theme';

interface FocusReticleProps {
    x: number;
    y: number;
    visible: boolean;
    onAnimationComplete?: () => void;
}

export function FocusReticle({ x, y, visible, onAnimationComplete }: FocusReticleProps) {
    const scale = useSharedValue(1.5);
    const opacity = useSharedValue(0);

    useEffect(() => {
        if (visible) {
            opacity.value = 1;
            scale.value = 1.5;
            scale.value = withSpring(1.0);

            // Auto hide after 2 seconds
            const timeout = setTimeout(() => {
                opacity.value = withTiming(0, { duration: 300 }, (finished) => {
                    if (finished && onAnimationComplete) {
                        runOnJS(onAnimationComplete)();
                    }
                });
            }, 2000);

            return () => clearTimeout(timeout);
        }
    }, [visible, x, y]);

    const style = useAnimatedStyle(() => ({
        left: x - 40, // Center the 80px box
        top: y - 40,
        opacity: opacity.value,
        transform: [{ scale: scale.value }],
    }));

    if (!visible) return null;

    return (
        <Animated.View style={[styles.container, style]}>
            <View style={[styles.corner, styles.topLeft]} />
            <View style={[styles.corner, styles.topRight]} />
            <View style={[styles.corner, styles.bottomLeft]} />
            <View style={[styles.corner, styles.bottomRight]} />
        </Animated.View>
    );
}

const styles = StyleSheet.create({
    container: {
        position: 'absolute',
        width: 80,
        height: 80,
        pointerEvents: 'none',
    },
    corner: {
        position: 'absolute',
        width: 16,
        height: 16,
        borderColor: COLORS.primary,
    },
    topLeft: {
        top: 0,
        left: 0,
        borderTopWidth: 2,
        borderLeftWidth: 2,
    },
    topRight: {
        top: 0,
        right: 0,
        borderTopWidth: 2,
        borderRightWidth: 2,
    },
    bottomLeft: {
        bottom: 0,
        left: 0,
        borderBottomWidth: 2,
        borderLeftWidth: 2,
    },
    bottomRight: {
        bottom: 0,
        right: 0,
        borderBottomWidth: 2,
        borderRightWidth: 2,
    },
});

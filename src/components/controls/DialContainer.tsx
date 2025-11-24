import React, { useEffect } from 'react';
import { View, StyleSheet, Dimensions } from 'react-native';
import Animated, {
    useAnimatedStyle,
    useSharedValue,
    withSpring,
    withDecay,
    runOnJS,
    interpolate,
    Extrapolate
} from 'react-native-reanimated';
import { GestureDetector, Gesture } from 'react-native-gesture-handler';
import * as Haptics from 'expo-haptics';
import { LinearGradient } from 'expo-linear-gradient';
import { COLORS, TYPOGRAPHY } from '../../theme';
import { useCameraStore } from '../../stores/cameraStore';

const { width: SCREEN_WIDTH } = Dimensions.get('window');
const TICK_SPACING = 60;

export function DialContainer() {
    const { activeControl, setIso, setShutter, setFocus } = useCameraStore();
    const translateX = useSharedValue(0);
    const activeIndex = useSharedValue(0);
    const context = useSharedValue({ startX: 0 });

    // Reset position when control changes
    useEffect(() => {
        translateX.value = withSpring(0);
    }, [activeControl]);

    const pan = Gesture.Pan()
        .onStart(() => {
            context.value = { startX: translateX.value };
        })
        .onUpdate((event) => {
            translateX.value = context.value.startX + event.translationX;
        })
        .onEnd((event) => {
            translateX.value = withDecay({
                velocity: event.velocityX,
                clamp: [-10000, 10000], // Adjust based on range
            });
        });

    // Haptics logic
    useAnimatedStyle(() => {
        const index = Math.round(-translateX.value / TICK_SPACING);
        if (index !== activeIndex.value) {
            activeIndex.value = index;
            runOnJS(Haptics.impactAsync)(Haptics.ImpactFeedbackStyle.Light);

            // Update store values based on index
            // This is a simplified mapping. Real implementation needs specific ranges for ISO/Shutter
            if (activeControl === 'iso') {
                const isoValues = [100, 200, 400, 800, 1600, 3200, 6400];
                const mappedIndex = Math.max(0, Math.min(isoValues.length - 1, index + 3)); // Offset center
                runOnJS(setIso)(isoValues[mappedIndex]);
            }
        }
        return {};
    });

    if (!activeControl) return null;

    return (
        <View style={styles.container}>
            <LinearGradient
                colors={['rgba(0,0,0,0)', 'rgba(0,0,0,0.8)', 'rgba(0,0,0,0)']}
                start={{ x: 0, y: 0 }}
                end={{ x: 1, y: 0 }}
                style={StyleSheet.absoluteFill}
                pointerEvents="none"
            />

            <GestureDetector gesture={pan}>
                <Animated.View style={styles.track}>
                    {Array.from({ length: 50 }).map((_, i) => {
                        const index = i - 25; // Center around 0

                        const rStyle = useAnimatedStyle(() => {
                            const position = (index * TICK_SPACING) + translateX.value;
                            const centerOffset = Math.abs(position);

                            const scale = interpolate(
                                centerOffset,
                                [0, SCREEN_WIDTH / 2],
                                [1.5, 0.5],
                                Extrapolate.CLAMP
                            );

                            const opacity = interpolate(
                                centerOffset,
                                [0, SCREEN_WIDTH / 2],
                                [1, 0.2],
                                Extrapolate.CLAMP
                            );

                            return {
                                transform: [
                                    { translateX: position },
                                    { scale }
                                ],
                                opacity
                            };
                        });

                        return (
                            <Animated.View key={i} style={[styles.tickContainer, rStyle]}>
                                <View style={styles.tick} />
                                {i % 5 === 0 && (
                                    <Animated.Text style={styles.tickLabel}>
                                        {/* Mock values */}
                                        {activeControl === 'shutter' ? `1/${(i + 1) * 10}` : (i + 1) * 100}
                                    </Animated.Text>
                                )}
                            </Animated.View>
                        );
                    })}
                </Animated.View>
            </GestureDetector>

            {/* Center Indicator */}
            <View style={styles.centerIndicator} />
        </View>
    );
}

const styles = StyleSheet.create({
    container: {
        position: 'absolute',
        bottom: 140, // Above dock
        width: '100%',
        height: 100,
        justifyContent: 'center',
        alignItems: 'center',
    },
    track: {
        flexDirection: 'row',
        alignItems: 'center',
        height: '100%',
    },
    tickContainer: {
        position: 'absolute',
        width: TICK_SPACING,
        justifyContent: 'center',
        alignItems: 'center',
    },
    tick: {
        width: 2,
        height: 24,
        backgroundColor: COLORS.white,
        borderRadius: 1,
    },
    tickLabel: {
        ...TYPOGRAPHY.data,
        color: COLORS.white,
        fontSize: 12,
        marginTop: 8,
        textAlign: 'center',
        width: 60,
    },
    centerIndicator: {
        position: 'absolute',
        width: 4,
        height: 40,
        backgroundColor: COLORS.primary,
        borderRadius: 2,
        top: 30,
    }
});

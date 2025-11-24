import React, { useEffect } from 'react';
import { View, StyleSheet } from 'react-native';
import Animated, {
    useAnimatedStyle,
    useSharedValue,
    withTiming,
    withSpring
} from 'react-native-reanimated';
import { LinearGradient } from 'expo-linear-gradient';
import { GlassView } from '../ui/GlassView';

export function AudioMeter() {
    // Mock audio level for now since we don't have real-time audio analysis hook set up yet
    // In a real app, we'd use a hook from expo-av or similar to get decibel levels
    const volume = useSharedValue(0);

    useEffect(() => {
        const interval = setInterval(() => {
            // Randomize volume between 0 and 100 for demo
            volume.value = withTiming(Math.random() * 100, { duration: 100 });
        }, 100);
        return () => clearInterval(interval);
    }, []);

    const animatedStyle = useAnimatedStyle(() => ({
        height: `${Math.max(5, volume.value)}%`,
    }));

    return (
        <GlassView style={styles.container}>
            <View style={styles.track}>
                <Animated.View style={[styles.fill, animatedStyle]}>
                    <LinearGradient
                        colors={['#4ADE80', '#FACC15', '#EF4444']}
                        locations={[0.5, 0.8, 1.0]}
                        style={styles.gradient}
                    />
                </Animated.View>
            </View>
        </GlassView>
    );
}

const styles = StyleSheet.create({
    container: {
        width: 24,
        height: 160,
        padding: 6,
        borderRadius: 12,
    },
    track: {
        flex: 1,
        backgroundColor: 'rgba(255,255,255,0.1)',
        borderRadius: 6,
        overflow: 'hidden',
        justifyContent: 'flex-end',
    },
    fill: {
        width: '100%',
        borderRadius: 6,
        overflow: 'hidden',
    },
    gradient: {
        flex: 1,
    },
});

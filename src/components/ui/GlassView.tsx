import React from 'react';
import { StyleSheet, ViewStyle, StyleProp } from 'react-native';
import { BlurView } from 'expo-blur';
import { COLORS } from '../../theme';

interface GlassViewProps {
    style?: StyleProp<ViewStyle>;
    children: React.ReactNode;
    intensity?: number;
}

export function GlassView({ style, children, intensity = 20 }: GlassViewProps) {
    return (
        <BlurView
            intensity={intensity}
            tint="dark"
            style={[styles.container, style]}
        >
            {children}
        </BlurView>
    );
}

const styles = StyleSheet.create({
    container: {
        backgroundColor: COLORS.glassBackground,
        borderColor: COLORS.glassBorder,
        borderWidth: 1,
        borderRadius: 16,
        overflow: 'hidden',
    },
});

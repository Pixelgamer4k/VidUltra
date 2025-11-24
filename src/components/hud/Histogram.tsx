import React from 'react';
import { View, StyleSheet } from 'react-native';
import Svg, { Path, Defs, LinearGradient, Stop } from 'react-native-svg';
import { GlassView } from '../ui/GlassView';
import { COLORS } from '../../theme';

export function Histogram() {
    // Simulated Bezier curve for Luma histogram
    // M startX startY C cp1x cp1y cp2x cp2y endX endY ...
    const pathData = `
    M 0 60 
    C 20 60, 30 40, 40 35
    S 60 50, 80 40
    S 100 10, 110 15
    S 120 60, 128 60
    L 128 60 L 0 60 Z
  `;

    return (
        <GlassView style={styles.container}>
            <View style={styles.content}>
                <Svg height="100%" width="100%" viewBox="0 0 128 60">
                    <Defs>
                        <LinearGradient id="grad" x1="0" y1="0" x2="0" y2="1">
                            <Stop offset="0" stopColor={COLORS.primary} stopOpacity="0.8" />
                            <Stop offset="1" stopColor={COLORS.primary} stopOpacity="0.1" />
                        </LinearGradient>
                    </Defs>
                    <Path
                        d={pathData}
                        fill="url(#grad)"
                        stroke={COLORS.primary}
                        strokeWidth="2"
                    />
                </Svg>
            </View>
        </GlassView>
    );
}

const styles = StyleSheet.create({
    container: {
        width: 140,
        height: 70,
        borderRadius: 12,
    },
    content: {
        flex: 1,
        padding: 8,
    },
});

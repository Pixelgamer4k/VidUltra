import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { GlassView } from '../ui/GlassView';
import { COLORS, TYPOGRAPHY } from '../../theme';
import { useCameraStore } from '../../stores/cameraStore';

export function TechSpecs() {
    const { selectedResolution, bitDepth } = useCameraStore();

    return (
        <View style={styles.container}>
            {/* Bitrate/Res */}
            <GlassView style={styles.specBox}>
                <View style={styles.activeIndicator} />
                <View style={styles.content}>
                    <Text style={styles.label}>RESOLUTION</Text>
                    <Text style={styles.value}>
                        {selectedResolution.label} <Text style={styles.unit}>HEVC</Text>
                    </Text>
                </View>
            </GlassView>

            {/* Codec/Depth */}
            <GlassView style={styles.specBox}>
                <View style={styles.content}>
                    <Text style={styles.label}>DEPTH</Text>
                    <Text style={styles.value}>{bitDepth}-BIT</Text>
                </View>
            </GlassView>
        </View>
    );
}

const styles = StyleSheet.create({
    container: {
        gap: 12,
        alignItems: 'flex-end',
    },
    specBox: {
        flexDirection: 'row',
        width: 120,
        height: 50,
        borderRadius: 12,
        overflow: 'hidden',
    },
    activeIndicator: {
        width: 4,
        height: '100%',
        backgroundColor: COLORS.primary,
    },
    content: {
        flex: 1,
        justifyContent: 'center',
        paddingHorizontal: 10,
    },
    label: {
        ...TYPOGRAPHY.label,
        color: COLORS.textSecondary,
        fontSize: 8,
        marginBottom: 2,
    },
    value: {
        ...TYPOGRAPHY.data,
        color: COLORS.white,
        fontSize: 14,
        fontWeight: 'bold',
    },
    unit: {
        fontSize: 10,
        color: COLORS.textSecondary,
        fontWeight: 'normal',
    }
});

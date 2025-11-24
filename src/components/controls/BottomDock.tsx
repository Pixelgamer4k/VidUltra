import React from 'react';
import { View, TouchableOpacity, Text, StyleSheet } from 'react-native';
import { Activity } from 'lucide-react-native';
import { GlassView } from '../ui/GlassView';
import { COLORS, TYPOGRAPHY } from '../../theme';
import { useCameraStore, ControlType } from '../../stores/cameraStore';

export function BottomDock() {
    const {
        isProMode,
        toggleProMode,
        iso,
        shutter,
        focus,
        activeControl,
        setActiveControl
    } = useCameraStore();

    const ControlItem = ({ label, value, type }: { label: string, value: string, type: ControlType }) => (
        <TouchableOpacity
            style={styles.controlItem}
            onPress={() => isProMode && setActiveControl(activeControl === type ? null : type)}
            disabled={!isProMode}
        >
            <Text style={[
                styles.controlLabel,
                activeControl === type && { color: COLORS.primary }
            ]}>
                {label}
            </Text>
            <Text style={[
                styles.controlValue,
                activeControl === type && { color: COLORS.white, textShadowColor: COLORS.primary, textShadowRadius: 10 }
            ]}>
                {value}
            </Text>
        </TouchableOpacity>
    );

    return (
        <GlassView style={[styles.container, !isProMode && { opacity: 0.8 }]}>
            <View style={styles.content}>
                {/* Auto/Pro Toggle */}
                <TouchableOpacity
                    style={styles.modeToggle}
                    onPress={toggleProMode}
                >
                    <Activity
                        size={24}
                        color={isProMode ? COLORS.primary : COLORS.white}
                    />
                    <Text style={[
                        styles.modeText,
                        isProMode && { color: COLORS.primary }
                    ]}>
                        {isProMode ? 'PRO' : 'AUTO'}
                    </Text>
                </TouchableOpacity>

                <View style={styles.divider} />

                {/* Controls */}
                <View style={[styles.controlsGroup, !isProMode && { opacity: 0.5 }]}>
                    <ControlItem
                        label="ISO"
                        value={isProMode ? iso.toString() : 'AUTO'}
                        type="iso"
                    />
                    <ControlItem
                        label="SHUTTER"
                        value={isProMode ? `1/${Math.round(1 / shutter)}` : '1/50'}
                        type="shutter"
                    />
                    <ControlItem
                        label="FOCUS"
                        value={isProMode ? focus.toFixed(2) : 'AUTO'}
                        type="focus"
                    />
                </View>
            </View>
        </GlassView>
    );
}

const styles = StyleSheet.create({
    container: {
        width: '90%',
        maxWidth: 400,
        height: 80,
        borderRadius: 24,
        alignSelf: 'center',
    },
    content: {
        flex: 1,
        flexDirection: 'row',
        alignItems: 'center',
    },
    modeToggle: {
        width: 80,
        height: '100%',
        justifyContent: 'center',
        alignItems: 'center',
        gap: 4,
    },
    modeText: {
        ...TYPOGRAPHY.label,
        fontSize: 10,
        color: COLORS.white,
    },
    divider: {
        width: 1,
        height: '60%',
        backgroundColor: 'rgba(255,255,255,0.1)',
    },
    controlsGroup: {
        flex: 1,
        flexDirection: 'row',
        justifyContent: 'space-evenly',
        alignItems: 'center',
    },
    controlItem: {
        alignItems: 'center',
        gap: 4,
    },
    controlLabel: {
        ...TYPOGRAPHY.label,
        color: COLORS.textSecondary,
        fontWeight: '700',
    },
    controlValue: {
        ...TYPOGRAPHY.data,
        fontSize: 16,
        color: COLORS.textSecondary,
        fontWeight: 'normal',
    },
});

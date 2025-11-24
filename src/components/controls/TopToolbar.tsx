import React from 'react';
import { View, TouchableOpacity, StyleSheet, Text } from 'react-native';
import { Grid3X3, Settings, Lock, Unlock, Smartphone } from 'lucide-react-native';
import { GlassView } from '../ui/GlassView';
import { COLORS, TYPOGRAPHY } from '../../theme';
import { useCameraStore } from '../../stores/cameraStore';

export function TopToolbar() {
    const {
        isEditMode,
        toggleEditMode,
        showGrid,
        toggleGrid,
        isLocked,
        toggleLock
    } = useCameraStore();

    return (
        <View style={styles.container}>
            {/* Layout Edit Button */}
            <TouchableOpacity onPress={toggleEditMode}>
                <GlassView style={[styles.button, styles.layoutButton]}>
                    <Text style={styles.layoutText}>
                        {isEditMode ? 'DONE' : 'LAYOUT'}
                    </Text>
                </GlassView>
            </TouchableOpacity>

            <View style={styles.rightGroup}>
                {/* Grid Toggle */}
                <TouchableOpacity onPress={toggleGrid}>
                    <GlassView style={styles.iconButton}>
                        <Grid3X3
                            size={20}
                            color={showGrid ? COLORS.primary : COLORS.white}
                        />
                    </GlassView>
                </TouchableOpacity>

                {/* Settings (Placeholder) */}
                <TouchableOpacity>
                    <GlassView style={styles.iconButton}>
                        <Settings size={20} color={COLORS.white} />
                    </GlassView>
                </TouchableOpacity>

                {/* Lock Toggle */}
                <TouchableOpacity onPress={toggleLock}>
                    <GlassView style={styles.iconButton}>
                        {isLocked ? (
                            <Lock size={20} color={COLORS.danger} />
                        ) : (
                            <Unlock size={20} color={COLORS.white} />
                        )}
                    </GlassView>
                </TouchableOpacity>
            </View>
        </View>
    );
}

const styles = StyleSheet.create({
    container: {
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        width: '100%',
        paddingHorizontal: 20,
    },
    rightGroup: {
        flexDirection: 'row',
        gap: 12,
    },
    button: {
        height: 44,
        justifyContent: 'center',
        alignItems: 'center',
    },
    layoutButton: {
        paddingHorizontal: 16,
        borderRadius: 22,
    },
    layoutText: {
        ...TYPOGRAPHY.label,
        color: COLORS.white,
        fontWeight: '700',
    },
    iconButton: {
        width: 44,
        height: 44,
        borderRadius: 22,
        justifyContent: 'center',
        alignItems: 'center',
    },
});

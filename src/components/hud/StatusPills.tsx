import React, { useState, useEffect } from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { Battery, Disc, Clock } from 'lucide-react-native';
import * as ExpoBattery from 'expo-battery';
import { GlassView } from '../ui/GlassView';
import { COLORS, TYPOGRAPHY } from '../../theme';
import { useCameraStore } from '../../stores/cameraStore';

export function StatusPills() {
    const [batteryLevel, setBatteryLevel] = useState<number>(100);
    const isRecording = useCameraStore(state => state.isRecording);
    const [duration, setDuration] = useState(0);

    useEffect(() => {
        const getBattery = async () => {
            const level = await ExpoBattery.getBatteryLevelAsync();
            setBatteryLevel(Math.round(level * 100));
        };
        getBattery();
        const sub = ExpoBattery.addBatteryLevelListener(({ batteryLevel }) => {
            setBatteryLevel(Math.round(batteryLevel * 100));
        });
        return () => sub.remove();
    }, []);

    useEffect(() => {
        let interval: NodeJS.Timeout;
        if (isRecording) {
            interval = setInterval(() => setDuration(d => d + 1), 1000);
        } else {
            setDuration(0);
        }
        return () => clearInterval(interval);
    }, [isRecording]);

    const formatTime = (seconds: number) => {
        const m = Math.floor(seconds / 60).toString().padStart(2, '0');
        const s = (seconds % 60).toString().padStart(2, '0');
        return `${m}:${s}`;
    };

    return (
        <View style={styles.container}>
            {/* Battery Pill */}
            <GlassView style={styles.pill}>
                <Battery
                    size={14}
                    color={batteryLevel > 20 ? '#4ADE80' : COLORS.danger}
                />
                <Text style={styles.text}>{batteryLevel}%</Text>
            </GlassView>

            {/* Storage/Timecode Pill */}
            <GlassView style={styles.pill}>
                {isRecording ? (
                    <Clock size={14} color={COLORS.danger} />
                ) : (
                    <Disc size={14} color="#60A5FA" />
                )}
                <Text style={[
                    styles.text,
                    isRecording && { color: COLORS.danger }
                ]}>
                    {isRecording ? `REC ${formatTime(duration)}` : '128G'}
                </Text>
            </GlassView>
        </View>
    );
}

const styles = StyleSheet.create({
    container: {
        flexDirection: 'column',
        gap: 8,
    },
    pill: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 6,
        paddingHorizontal: 10,
        paddingVertical: 6,
        borderRadius: 20, // Pill shape
    },
    text: {
        ...TYPOGRAPHY.data,
        fontSize: 12,
        color: COLORS.white,
    },
});

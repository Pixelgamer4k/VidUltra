import React from 'react';
import { View, StyleSheet, Text } from 'react-native';
import { GestureDetector, Gesture } from 'react-native-gesture-handler';
import Animated, {
    useAnimatedStyle,
    useSharedValue,
    withSpring,
    runOnJS
} from 'react-native-reanimated';
import { Move, Maximize } from 'lucide-react-native';
import { COLORS, TYPOGRAPHY } from '../../theme';
import { useCameraStore } from '../../stores/cameraStore';

interface DraggableItemProps {
    id: string;
    children: React.ReactNode;
    defaultPosition?: { x: number; y: number };
}

export function DraggableItem({ id, children, defaultPosition = { x: 0, y: 0 } }: DraggableItemProps) {
    const { isEditMode, layout, updateLayout } = useCameraStore();

    // Initialize from store or default
    const savedConfig = layout[id] || { ...defaultPosition, scale: 1 };

    const translateX = useSharedValue(savedConfig.x);
    const translateY = useSharedValue(savedConfig.y);
    const scale = useSharedValue(savedConfig.scale);
    const context = useSharedValue({ x: 0, y: 0 });

    // Sync with store updates (if reset happens)
    React.useEffect(() => {
        translateX.value = withSpring(layout[id]?.x ?? defaultPosition.x);
        translateY.value = withSpring(layout[id]?.y ?? defaultPosition.y);
    }, [layout[id], defaultPosition]);

    const pan = Gesture.Pan()
        .onStart(() => {
            context.value = { x: translateX.value, y: translateY.value };
        })
        .onUpdate((event) => {
            translateX.value = context.value.x + event.translationX;
            translateY.value = context.value.y + event.translationY;
        })
        .onEnd(() => {
            runOnJS(updateLayout)(id, { x: translateX.value, y: translateY.value });
        });

    const animatedStyle = useAnimatedStyle(() => ({
        transform: [
            { translateX: translateX.value },
            { translateY: translateY.value },
            { scale: scale.value }
        ],
        position: 'absolute',
        zIndex: isEditMode ? 100 : 50, // Bring to front when editing
    }));

    return (
        <Animated.View style={animatedStyle} pointerEvents="box-none">
            <View style={[styles.content, isEditMode && styles.editingContent]} pointerEvents="auto">
                {children}

                {isEditMode && (
                    <>
                        {/* Drag Handle (Top Left) */}
                        <GestureDetector gesture={pan}>
                            <Animated.View style={styles.dragHandle}>
                                <View style={styles.handleLabel}>
                                    <Move size={12} color={COLORS.background} />
                                    <Text style={styles.handleText}>Drag</Text>
                                </View>
                            </Animated.View>
                        </GestureDetector>

                        {/* Resize Handle (Bottom Right) - Placeholder logic for now */}
                        <View style={styles.resizeHandle}>
                            <Maximize size={14} color={COLORS.background} />
                        </View>
                    </>
                )}
            </View>
        </Animated.View>
    );
}

const styles = StyleSheet.create({
    content: {
        // Base content container
    },
    editingContent: {
        borderWidth: 2,
        borderColor: COLORS.primary,
        borderStyle: 'dashed',
        borderRadius: 12,
        padding: 4,
    },
    dragHandle: {
        position: 'absolute',
        top: -24,
        left: 0,
        backgroundColor: COLORS.primary,
        borderRadius: 12,
        paddingHorizontal: 8,
        paddingVertical: 4,
        height: 24,
        justifyContent: 'center',
        alignItems: 'center',
    },
    handleLabel: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 4,
    },
    handleText: {
        ...TYPOGRAPHY.labelSmall,
        color: COLORS.background,
    },
    resizeHandle: {
        position: 'absolute',
        bottom: -16,
        right: -16,
        backgroundColor: COLORS.white,
        width: 24,
        height: 24,
        borderRadius: 12,
        justifyContent: 'center',
        alignItems: 'center',
    },
});

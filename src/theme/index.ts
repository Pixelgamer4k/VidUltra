export const COLORS = {
    primary: '#FACC15', // Yellow-400
    danger: '#EF4444',  // Red-500
    background: '#000000', // True Black
    surface: '#171717', // Neutral-900
    textSecondary: '#A3A3A3', // Neutral-400
    white: '#FFFFFF',
    glassBorder: 'rgba(255, 255, 255, 0.1)',
    glassBackground: 'rgba(0, 0, 0, 0.4)',
};

export const TYPOGRAPHY = {
    data: {
        fontFamily: 'monospace', // Platform specific handling needed in component
        fontSize: 14,
    },
    label: {
        fontFamily: 'System', // San Francisco / Roboto
        fontWeight: '700',
        fontSize: 10,
        letterSpacing: 1,
        textTransform: 'uppercase',
    },
    labelSmall: {
        fontFamily: 'System',
        fontWeight: '700',
        fontSize: 8,
        letterSpacing: 0.5,
        textTransform: 'uppercase',
    }
} as const;

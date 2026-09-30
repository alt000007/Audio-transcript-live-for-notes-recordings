interface Props {
  kind?: 'error' | 'info' | 'warn'
  children: React.ReactNode
  onDismiss?: () => void
}

export function Banner({ kind = 'info', children, onDismiss }: Props) {
  return (
    <div className={`banner ${kind}`} role={kind === 'error' ? 'alert' : 'status'}>
      <div className="grow">{children}</div>
      {onDismiss && (
        <button className="btn ghost sm" onClick={onDismiss} aria-label="Dismiss">
          ×
        </button>
      )}
    </div>
  )
}

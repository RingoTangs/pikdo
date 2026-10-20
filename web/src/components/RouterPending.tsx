export const RouterPending: React.FC = () => (
  <div
    role="status"
    className="bg-background text-muted-foreground flex min-h-screen items-center justify-center gap-3"
  >
    <span
      aria-hidden="true"
      className="border-primary size-6 rounded-full border-2 border-t-transparent motion-safe:animate-spin"
    />
    <span>Loading...</span>
  </div>
)

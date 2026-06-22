import type { ExceptionStatus } from '../types';

interface Props {
  status: ExceptionStatus;
}

export default function StatusBadge({ status }: Props) {
  return (
    <span className={`status-badge ${status.toLowerCase()}`}>
      {status}
    </span>
  );
}

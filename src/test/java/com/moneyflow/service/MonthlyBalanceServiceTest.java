package com.moneyflow.service;

import static com.moneyflow.domain.TestTransactions.credit;
import static com.moneyflow.domain.TestTransactions.history;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.moneyflow.domain.BalanceCalculator;
import com.moneyflow.domain.MonthlySummary;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;

class MonthlyBalanceServiceTest {

    private final TransactionSource source = mock(TransactionSource.class);
    private final SummaryPublisher publisher = mock(SummaryPublisher.class);
    private final MonthlyBalanceService service = new MonthlyBalanceService(source, new BalanceCalculator(), publisher);

    @Test
    void publishesEveryMonthOldestFirstAndReturnsTheSummaries() {
        when(source.fetch("ACC-123")).thenReturn(history(
                credit("t3", "2026-09-01", "3.00"),
                credit("t1", "2026-07-01", "1.00"),
                credit("t2", "2026-08-01", "2.00")));

        List<MonthlySummary> summaries = service.calculateAndPublish("ACC-123");

        assertThat(summaries).extracting(MonthlySummary::month)
                .containsExactly(YearMonth.of(2026, 7), YearMonth.of(2026, 8), YearMonth.of(2026, 9));
        InOrder inOrder = inOrder(publisher);
        summaries.forEach(summary -> inOrder.verify(publisher).publish(summary));
        verifyNoMoreInteractions(publisher);
    }

    @Test
    void stopsAtTheFirstFailedPublish() {
        when(source.fetch("ACC-123")).thenReturn(history(
                credit("t1", "2026-07-01", "1.00"),
                credit("t2", "2026-08-01", "2.00"),
                credit("t3", "2026-09-01", "3.00")));
        UpstreamException failure = new UpstreamException("Balance API down");
        doThrow(failure).when(publisher).publish(argThat(month(2026, 8)));

        assertThatThrownBy(() -> service.calculateAndPublish("ACC-123")).isSameAs(failure);

        verify(publisher).publish(argThat(month(2026, 7)));
        verify(publisher, never()).publish(argThat(month(2026, 9)));
    }

    @Test
    void publishesNothingWhenThereAreNoTransactions() {
        when(source.fetch("ACC-123")).thenReturn(history());

        assertThat(service.calculateAndPublish("ACC-123")).isEmpty();

        verify(publisher, never()).publish(any());
    }

    @Test
    void propagatesSourceFailuresWithoutPublishing() {
        when(source.fetch("NOPE")).thenThrow(new AccountNotFoundException("NOPE"));

        assertThatThrownBy(() -> service.calculateAndPublish("NOPE")).isInstanceOf(AccountNotFoundException.class);

        verify(publisher, never()).publish(any());
    }

    private static ArgumentMatcher<MonthlySummary> month(int year, int month) {
        return summary -> summary != null && summary.month().equals(YearMonth.of(year, month));
    }
}

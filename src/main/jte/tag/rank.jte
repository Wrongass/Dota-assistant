@import dota.analysis.Fmt
@param int rankTier
!{int medal = rankTier / 10; int star = rankTier % 10;}
@if(medal >= 1 && medal <= 8)
<span class="rank" title="${Fmt.rank(rankTier)}"><img class="rank-ico" src="/img/ranks/rank_icon_${medal}.png" alt="">@if(medal < 8 && star >= 1 && star <= 5)<img class="rank-star" src="/img/ranks/rank_star_${star}.png" alt="">@endif</span>
@else
<span class="muted">—</span>
@endif

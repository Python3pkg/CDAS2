#!/usr/bin/env bash

datafile="http://esgf.nccs.nasa.gov/thredds/dodsC/CMIP5/NASA/GISS/historical/E2-H_historical_r1i1p1/tas_Amon_GISS-E2-H_historical_r1i1p1_185001-190012.nc"
ncks -O -v tas -d lat,0,5 -d lon,0,5 -d time,0,0 ${datafile} /tmp/subset.nc
ncdump /tmp/subset.nc

# ncks -O -v tas -d lat,0.,0. -d lon,0.,0. ${datafile} out/subset_xy00_GISS_r1i1p1_185001-190012.nc

# ncks -O -v tas -d lat,10,15 -d lon,5,10 -d time,10,10 ${datafile} subset.nc

# ncks -O -v tas  -d time,0,10 ${datafile} GISS-r1i1p1-sample.nc

#ncwa -O -v tas -a time -d lat,5,5 -d lon,5,10 ${datafile} ~/test/out/tsubset.nc